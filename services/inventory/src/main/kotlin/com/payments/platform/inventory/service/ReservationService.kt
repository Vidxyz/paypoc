package com.payments.platform.inventory.service

import com.payments.platform.inventory.domain.Inventory
import com.payments.platform.inventory.domain.Reservation
import com.payments.platform.inventory.domain.ReservationStatus
import com.payments.platform.inventory.domain.ReservationType
import com.payments.platform.inventory.domain.TransactionType
import com.payments.platform.inventory.kafka.InventoryKafkaProducer
import com.payments.platform.inventory.kafka.ReservationCancelledEvent
import com.payments.platform.inventory.kafka.ReservationConfirmedEvent
import com.payments.platform.inventory.kafka.ReservationCreatedEvent
import com.payments.platform.inventory.kafka.ReservationFulfilledEvent
import com.payments.platform.inventory.kafka.StockUpdatedEvent
import com.payments.platform.inventory.persistence.InventoryRepository
import com.payments.platform.inventory.persistence.InventoryTransactionRepository
import com.payments.platform.inventory.persistence.ReservationEntity
import com.payments.platform.inventory.persistence.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val inventoryRepository: InventoryRepository,
    private val transactionRepository: InventoryTransactionRepository,
    private val kafkaProducer: InventoryKafkaProducer,
    @Autowired(required = false) private val cartServiceClient: CartServiceClient? = null  // Optional to avoid circular dependencies
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    // todo-vh: When an order is refunded wholly or partially, inventory is NOT returned. This needs to be implemented
    /**
     * Create a soft reservation (add-to-cart)
     * Moves stock from available to reserved
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun createSoftReservation(
        inventoryId: UUID,
        cartId: UUID,
        quantity: Int
    ): Reservation {
        if (quantity <= 0) {
            throw IllegalArgumentException("Reservation quantity must be positive")
        }
        
        return executeWithRetry(maxRetries = 10) {
            val inventoryEntity = inventoryRepository.findById(inventoryId)
                .orElseThrow { NoSuchElementException("Inventory not found: $inventoryId") }
            
            val inventory = inventoryEntity.toDomain()
            
            if (!inventory.canReserve(quantity)) {
                throw IllegalStateException("Not enough stock available. Available: ${inventory.availableQuantity}, Requested: $quantity")
            }
            
            // Soft reserve: move from available to reserved
            val updated = inventory.softReserve(quantity)
            updateInventoryEntity(inventoryEntity, updated)
            inventoryRepository.save(inventoryEntity)
            
            // Create reservation (15min TTL for SOFT)
            val reservationEntity = ReservationEntity(
                inventoryId = inventoryId,
                cartId = cartId,
                quantity = quantity,
                reservationType = ReservationType.SOFT,
                status = ReservationStatus.ACTIVE,
                expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)
            )
            val saved = reservationRepository.save(reservationEntity)
            
            // Record transaction
            recordTransaction(
                inventoryId = inventoryId,
                transactionType = TransactionType.RESERVE,
                quantity = quantity,
                referenceId = cartId,
                description = "Soft reservation (add-to-cart)"
            )
            
            logger.info("Created SOFT reservation ${saved.id} for cart $cartId, inventory $inventoryId, quantity $quantity")
            
            kafkaProducer.publishReservationCreatedEvent(ReservationCreatedEvent(
                reservationId = saved.id,
                productId = inventory.productId,
                orderId = cartId, // Using cartId as orderId for now
                quantity = saved.quantity,
                expiresAt = saved.expiresAt
            ))
            
            // Publish StockUpdatedEvent so catalog service can update denormalized inventory cache
            kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                stockId = inventoryEntity.id,
                productId = inventoryEntity.productId,
                availableQuantity = inventoryEntity.availableQuantity,
                totalQuantity = inventoryEntity.totalQuantity,
                reservedQuantity = inventoryEntity.reservedQuantity,
                allocatedQuantity = inventoryEntity.allocatedQuantity
            ))
            
            saved.toDomain()
        }
    }
    
    /**
     * Create a hard allocation (checkout)
     * Moves stock from reserved to allocated
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun createHardAllocation(
        inventoryId: UUID,
        cartId: UUID,
        quantity: Int
    ): Reservation {
        if (quantity <= 0) {
            throw IllegalArgumentException("Allocation quantity must be positive")
        }
        
        return executeWithRetry(maxRetries = 10) {
            val inventoryEntity = inventoryRepository.findById(inventoryId)
                .orElseThrow { NoSuchElementException("Inventory not found: $inventoryId") }
            
            val inventory = inventoryEntity.toDomain()
            
            if (!inventory.canAllocate(quantity)) {
                throw IllegalStateException("Not enough reserved stock. Reserved: ${inventory.reservedQuantity}, Requested: $quantity")
            }
            
            // Hard allocate: move from reserved to allocated
            val updated = inventory.hardAllocate(quantity)
            updateInventoryEntity(inventoryEntity, updated)
            inventoryRepository.save(inventoryEntity)
            
            // Create hard reservation (30min TTL for HARD, until payment completes/fails)
            val reservationEntity = ReservationEntity(
                inventoryId = inventoryId,
                cartId = cartId,
                quantity = quantity,
                reservationType = ReservationType.HARD,
                status = ReservationStatus.ALLOCATED,
                expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES)
            )
            val saved = reservationRepository.save(reservationEntity)
            
            // Record transaction
            recordTransaction(
                inventoryId = inventoryId,
                transactionType = TransactionType.ALLOCATE,
                quantity = quantity,
                referenceId = cartId,
                description = "Hard allocation (checkout)"
            )
            
            logger.info("Created HARD allocation ${saved.id} for cart $cartId, inventory $inventoryId, quantity $quantity")
            
            kafkaProducer.publishReservationConfirmedEvent(ReservationConfirmedEvent(
                reservationId = saved.id,
                orderId = cartId,
                productId = inventory.productId,
                quantity = saved.quantity
            ))
            
            // Publish StockUpdatedEvent so catalog service can update denormalized inventory cache
            kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                stockId = inventoryEntity.id,
                productId = inventoryEntity.productId,
                availableQuantity = inventoryEntity.availableQuantity,
                totalQuantity = inventoryEntity.totalQuantity,
                reservedQuantity = inventoryEntity.reservedQuantity,
                allocatedQuantity = inventoryEntity.allocatedQuantity
            ))
            
            saved.toDomain()
        }
    }
    
    /**
     * Allocate reservation (convert soft reservation to hard allocation)
     * Moves stock from reserved to allocated
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun allocateReservation(reservationId: UUID, orderId: UUID): Reservation {
        return executeWithRetry(maxRetries = 10) {
            val reservation = reservationRepository.findById(reservationId)
                .orElseThrow { NoSuchElementException("Reservation not found: $reservationId") }
            
            if (reservation.status != ReservationStatus.ACTIVE) {
                throw IllegalStateException("Reservation ${reservation.id} is not ACTIVE, cannot allocate. Current state: ${reservation.status}")
            }
            
            if (reservation.reservationType != ReservationType.SOFT) {
                throw IllegalStateException("Reservation ${reservation.id} is not a SOFT reservation, cannot allocate. Type: ${reservation.reservationType}")
            }
            
            val inventoryEntity = inventoryRepository.findById(reservation.inventoryId)
                .orElseThrow { NoSuchElementException("Inventory not found: ${reservation.inventoryId}") }
            
            val inventory = inventoryEntity.toDomain()
            
            if (!inventory.canAllocate(reservation.quantity)) {
                throw IllegalStateException("Not enough reserved stock. Reserved: ${inventory.reservedQuantity}, Requested: ${reservation.quantity}")
            }
            
            // Hard allocate: move from reserved to allocated
            val updated = inventory.hardAllocate(reservation.quantity)
            updateInventoryEntity(inventoryEntity, updated)
            inventoryRepository.save(inventoryEntity)
            
            // Update reservation status to ALLOCATED
            // Note: reservationType, cartId, and expiresAt are immutable (val), so we only update status
            // The cartId remains as the original cart ID, which is fine for tracking purposes
            reservation.status = ReservationStatus.ALLOCATED
            
            val saved = reservationRepository.save(reservation)
            
            // Record transaction
            recordTransaction(
                inventoryId = reservation.inventoryId,
                transactionType = TransactionType.ALLOCATE,
                quantity = reservation.quantity,
                referenceId = orderId,
                description = "Soft reservation converted to hard allocation (checkout)"
            )
            
            logger.info("Allocated reservation ${saved.id} for order $orderId, inventory ${reservation.inventoryId}, quantity ${reservation.quantity}")
            
            kafkaProducer.publishReservationConfirmedEvent(ReservationConfirmedEvent(
                reservationId = saved.id,
                orderId = orderId,
                productId = inventory.productId,
                quantity = saved.quantity
            ))
            
            // Publish StockUpdatedEvent so catalog service can update denormalized inventory cache
            kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                stockId = inventoryEntity.id,
                productId = inventoryEntity.productId,
                availableQuantity = inventoryEntity.availableQuantity,
                totalQuantity = inventoryEntity.totalQuantity,
                reservedQuantity = inventoryEntity.reservedQuantity,
                allocatedQuantity = inventoryEntity.allocatedQuantity
            ))
            
            saved.toDomain()
        }
    }
    
    /**
     * Release a reservation (soft or hard)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun releaseReservation(reservationId: UUID): Reservation {
        return executeWithRetry(maxRetries = 10) {
            val reservation = reservationRepository.findById(reservationId)
                .orElseThrow { NoSuchElementException("Reservation not found: $reservationId") }
            
            if (reservation.status == ReservationStatus.RELEASED) {
                logger.warn("Reservation ${reservation.id} is already RELEASED. Idempotent operation.")
                return@executeWithRetry reservation.toDomain()
            }
            
            if (reservation.status == ReservationStatus.SOLD) {
                throw IllegalStateException("Cannot release a SOLD reservation ${reservation.id}")
            }
            
            val inventoryEntity = inventoryRepository.findById(reservation.inventoryId)
                .orElseThrow { NoSuchElementException("Inventory not found: ${reservation.inventoryId}") }
            
            val inventory = inventoryEntity.toDomain()
            
            // Release based on reservation status, not type
            // A SOFT reservation that was converted to ALLOCATED still has reservationType=SOFT,
            // but the stock is in allocated, not reserved
            val updated = when (reservation.status) {
                ReservationStatus.ACTIVE -> {
                    // Release soft reservation: move from reserved back to available
                    inventory.releaseReservation(reservation.quantity)
                }
                ReservationStatus.ALLOCATED -> {
                    // Release hard allocation: move from allocated back to available
                    inventory.releaseAllocation(reservation.quantity)
                }
                else -> {
                    throw IllegalStateException("Cannot release reservation ${reservation.id} with status ${reservation.status}. Only ACTIVE and ALLOCATED reservations can be released.")
                }
            }
            
            updateInventoryEntity(inventoryEntity, updated)
            inventoryRepository.save(inventoryEntity)
            
            reservation.status = ReservationStatus.RELEASED
            val saved = reservationRepository.save(reservation)
            
            // Record transaction
            recordTransaction(
                inventoryId = reservation.inventoryId,
                transactionType = TransactionType.RELEASE,
                quantity = reservation.quantity,
                referenceId = reservation.cartId,
                description = "Reservation released"
            )
            
            logger.info("Released reservation ${saved.id} for cart ${saved.cartId} and returned ${saved.quantity} to stock")
            
            kafkaProducer.publishReservationCancelledEvent(ReservationCancelledEvent(
                reservationId = saved.id,
                orderId = saved.cartId,
                productId = inventory.productId,
                quantity = saved.quantity
            ))
            
            // Publish StockUpdatedEvent so catalog service can update denormalized inventory cache
            kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                stockId = inventoryEntity.id,
                productId = inventoryEntity.productId,
                availableQuantity = inventoryEntity.availableQuantity,
                totalQuantity = inventoryEntity.totalQuantity,
                reservedQuantity = inventoryEntity.reservedQuantity,
                allocatedQuantity = inventoryEntity.allocatedQuantity
            ))
            
            saved.toDomain()
        }
    }
    
    /**
     * Confirm sale (after payment)
     * Moves stock from allocated to sold (deducts from total)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun confirmSale(reservationId: UUID): Reservation {
        return executeWithRetry(maxRetries = 10) {
            val reservation = reservationRepository.findById(reservationId)
                .orElseThrow { NoSuchElementException("Reservation not found: $reservationId") }
            
            if (reservation.status != ReservationStatus.ALLOCATED) {
                throw IllegalStateException("Reservation ${reservation.id} is not ALLOCATED, cannot confirm sale. Current state: ${reservation.status}")
            }
            
            val inventoryEntity = inventoryRepository.findById(reservation.inventoryId)
                .orElseThrow { NoSuchElementException("Inventory not found: ${reservation.inventoryId}") }
            
            val inventory = inventoryEntity.toDomain()
            
            // Confirm sale: move from allocated to sold (deduct from total)
            val updated = inventory.confirmSale(reservation.quantity)
            updateInventoryEntity(inventoryEntity, updated)
            inventoryRepository.save(inventoryEntity)
            
            reservation.status = ReservationStatus.SOLD
            val saved = reservationRepository.save(reservation)
            
            // Record transaction
            recordTransaction(
                inventoryId = reservation.inventoryId,
                transactionType = TransactionType.SELL,
                quantity = reservation.quantity,
                referenceId = reservation.cartId,
                description = "Sale confirmed (payment completed)"
            )
            
            logger.info("Confirmed sale for reservation ${saved.id}, order ${saved.cartId}, quantity ${saved.quantity}")
            
            kafkaProducer.publishReservationFulfilledEvent(ReservationFulfilledEvent(
                reservationId = saved.id,
                orderId = saved.cartId,
                productId = inventory.productId,
                quantity = saved.quantity
            ))
            
            // Publish StockUpdatedEvent so catalog service can update denormalized inventory cache
            kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                stockId = inventoryEntity.id,
                productId = inventoryEntity.productId,
                availableQuantity = inventoryEntity.availableQuantity,
                totalQuantity = inventoryEntity.totalQuantity,
                reservedQuantity = inventoryEntity.reservedQuantity,
                allocatedQuantity = inventoryEntity.allocatedQuantity
            ))
            
            saved.toDomain()
        }
    }
    
    @Transactional(readOnly = true)
    fun getReservationById(reservationId: UUID): Reservation? {
        return reservationRepository.findById(reservationId).orElse(null)?.toDomain()
    }
    
    @Transactional(readOnly = true)
    fun getReservationsByCartId(cartId: UUID): List<Reservation> {
        return reservationRepository.findByCartId(cartId).map { it.toDomain() }
    }
    
    /**
     * Cleanup expired reservations
     * Finds all expired ACTIVE and ALLOCATED reservations and releases them
     * Returns summary of cleanup operation
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun cleanupExpiredReservations(): CleanupResult {
        val now = Instant.now()
        logger.info("Starting cleanup of expired reservations at $now")
        
        var expiredCount = 0
        var releasedCount = 0
        var errorCount = 0
        
        // Find expired ACTIVE reservations (soft reservations)
        val expiredActive = reservationRepository.findExpiredReservations(ReservationStatus.ACTIVE, now)
        logger.info("Found ${expiredActive.size} expired ACTIVE reservations")
        
        for (reservationEntity in expiredActive) {
            try {
                // Check if cart still exists before releasing
                val cartExists = cartServiceClient?.checkCartExists(reservationEntity.cartId)
                if (cartExists == true) {
                    logger.info("Skipping release of expired reservation ${reservationEntity.id} - cart ${reservationEntity.cartId} still exists")
                    expiredCount++ // Count as expired but not released
                    continue
                } else if (cartExists == null) {
                    // Error checking cart - assume cart exists to be safe
                    logger.warn("Error checking cart existence for reservation ${reservationEntity.id}, cart ${reservationEntity.cartId}. Skipping release to be safe.")
                    expiredCount++
                    continue
                }
                
                // Cart doesn't exist - safe to release
                // Release the reservation (this will move stock back to available)
                // This publishes ReservationCancelledEvent automatically
                releaseReservation(reservationEntity.id)
                expiredCount++
                releasedCount++
                
                // Publish expired event to indicate it expired (in addition to cancelled event)
                val inventoryEntity = inventoryRepository.findById(reservationEntity.inventoryId)
                    .orElse(null)
                if (inventoryEntity != null) {
                    kafkaProducer.publishReservationExpiredEvent(
                        com.payments.platform.inventory.kafka.ReservationExpiredEvent(
                            reservationId = reservationEntity.id,
                            orderId = reservationEntity.cartId,
                            productId = inventoryEntity.productId,
                            quantity = reservationEntity.quantity,
                            reservationType = reservationEntity.reservationType.name
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Error releasing expired ACTIVE reservation ${reservationEntity.id}: ${e.message}", e)
                errorCount++
                // Mark as expired even if release failed
                try {
                    val entity = reservationRepository.findById(reservationEntity.id).orElse(null)
                    if (entity != null) {
                        entity.status = ReservationStatus.EXPIRED
                        reservationRepository.save(entity)
                        expiredCount++
                    }
                } catch (saveError: Exception) {
                    logger.error("Error marking reservation ${reservationEntity.id} as EXPIRED: ${saveError.message}", saveError)
                }
            }
        }
        
        // Find expired ALLOCATED reservations (hard allocations)
        val expiredAllocated = reservationRepository.findExpiredReservations(ReservationStatus.ALLOCATED, now)
        logger.info("Found ${expiredAllocated.size} expired ALLOCATED reservations")
        
        for (reservationEntity in expiredAllocated) {
            try {
                // Check if cart still exists before releasing
                val cartExists = cartServiceClient?.checkCartExists(reservationEntity.cartId)
                if (cartExists == true) {
                    logger.info("Skipping release of expired ALLOCATED reservation ${reservationEntity.id} - cart ${reservationEntity.cartId} still exists")
                    expiredCount++ // Count as expired but not released
                    continue
                } else if (cartExists == null) {
                    // Error checking cart - assume cart exists to be safe
                    logger.warn("Error checking cart existence for ALLOCATED reservation ${reservationEntity.id}, cart ${reservationEntity.cartId}. Skipping release to be safe.")
                    expiredCount++
                    continue
                }
                
                // Cart doesn't exist - safe to release
                // Release the reservation (this will move stock back to available)
                // This publishes ReservationCancelledEvent automatically
                releaseReservation(reservationEntity.id)
                expiredCount++
                releasedCount++
                
                // Publish expired event to indicate it expired (in addition to cancelled event)
                val inventoryEntity = inventoryRepository.findById(reservationEntity.inventoryId)
                    .orElse(null)
                if (inventoryEntity != null) {
                    kafkaProducer.publishReservationExpiredEvent(
                        com.payments.platform.inventory.kafka.ReservationExpiredEvent(
                            reservationId = reservationEntity.id,
                            orderId = reservationEntity.cartId,
                            productId = inventoryEntity.productId,
                            quantity = reservationEntity.quantity,
                            reservationType = reservationEntity.reservationType.name
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Error releasing expired ALLOCATED reservation ${reservationEntity.id}: ${e.message}", e)
                errorCount++
                // Mark as expired even if release failed
                try {
                    val entity = reservationRepository.findById(reservationEntity.id).orElse(null)
                    if (entity != null) {
                        entity.status = ReservationStatus.EXPIRED
                        reservationRepository.save(entity)
                        expiredCount++
                    }
                } catch (saveError: Exception) {
                    logger.error("Error marking reservation ${reservationEntity.id} as EXPIRED: ${saveError.message}", saveError)
                }
            }
        }
        
        logger.info("Cleanup completed: expired=$expiredCount, released=$releasedCount, errors=$errorCount")
        return CleanupResult(expiredCount, releasedCount, errorCount)
    }
    
    /**
     * Result of cleanup operation
     */
    data class CleanupResult(
        val expiredCount: Int,
        val releasedCount: Int,
        val errorCount: Int
    )
    
    /**
     * Execute a function with retry logic for optimistic locking failures.
     * Handles OptimisticLockingFailureException and serialization failures (SQL state 40001).
     */
    private fun <T> executeWithRetry(maxRetries: Int, block: () -> T): T {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: IllegalArgumentException) {
                // Don't retry validation failures
                throw e
            } catch (e: NoSuchElementException) {
                // Don't retry "not found" errors
                throw e
            } catch (e: IllegalStateException) {
                // Don't retry business logic failures (e.g., insufficient stock)
                throw e
            } catch (e: Exception) {
                // Check if this is an optimistic locking failure or serialization failure
                var current: Throwable? = e
                var isRetryable = false
                
                while (current != null) {
                    when (current) {
                        is OptimisticLockingFailureException -> {
                            isRetryable = true
                            break
                        }
                        is SQLException -> {
                            if (current.sqlState == "40001" || current.message?.contains("serialize") == true) {
                                isRetryable = true
                                break
                            }
                        }
                        is CannotAcquireLockException -> {
                            val sqlException = current.cause
                            if (sqlException is SQLException && sqlException.sqlState == "40001") {
                                isRetryable = true
                                break
                            }
                        }
                    }
                    current = current.cause
                }
                
                if (isRetryable && attempt < maxRetries - 1) {
                    lastException = e
                    val delay = (10 * (1 shl attempt)).toLong() // Exponential backoff: 10ms, 20ms, 40ms, ...
                    logger.debug("Retrying reservation operation after optimistic locking failure (attempt ${attempt + 1}/$maxRetries, delay ${delay}ms)")
                    Thread.sleep(delay)
                } else if (isRetryable) {
                    throw RuntimeException("Failed reservation operation after $maxRetries retries due to optimistic locking conflicts", e)
                } else {
                    throw e
                }
            }
        }
        
        throw RuntimeException("Failed reservation operation after $maxRetries retries due to optimistic locking conflicts", lastException)
    }
    
    private fun recordTransaction(
        inventoryId: UUID,
        transactionType: TransactionType,
        quantity: Int,
        referenceId: UUID? = null,
        description: String? = null
    ) {
        val transaction = com.payments.platform.inventory.persistence.InventoryTransactionEntity(
            inventoryId = inventoryId,
            transactionType = transactionType,
            quantity = quantity,
            referenceId = referenceId,
            description = description
        )
        transactionRepository.save(transaction)
    }
    
    private fun ReservationEntity.toDomain(): Reservation {
        return Reservation(
            id = this.id,
            inventoryId = this.inventoryId,
            cartId = this.cartId,
            quantity = this.quantity,
            reservationType = this.reservationType,
            status = this.status,
            expiresAt = this.expiresAt,
            createdAt = this.createdAt
        )
    }
    
    private fun com.payments.platform.inventory.persistence.InventoryEntity.toDomain(): Inventory {
        return Inventory(
            id = this.id,
            productId = this.productId,
            sellerId = this.sellerId,
            sku = this.sku,
            availableQuantity = this.availableQuantity,
            reservedQuantity = this.reservedQuantity,
            allocatedQuantity = this.allocatedQuantity,
            totalQuantity = this.totalQuantity,
            lowStockThreshold = this.lowStockThreshold
        )
    }
    
    private fun updateInventoryEntity(
        entity: com.payments.platform.inventory.persistence.InventoryEntity,
        inventory: Inventory
    ) {
        entity.availableQuantity = inventory.availableQuantity
        entity.reservedQuantity = inventory.reservedQuantity
        entity.allocatedQuantity = inventory.allocatedQuantity
        entity.totalQuantity = inventory.totalQuantity
        entity.lowStockThreshold = inventory.lowStockThreshold
    }
}
