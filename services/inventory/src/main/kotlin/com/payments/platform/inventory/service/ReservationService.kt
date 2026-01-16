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
    private val kafkaProducer: InventoryKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
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
            
            // Release based on reservation type
            val updated = when (reservation.reservationType) {
                ReservationType.SOFT -> {
                    // Release soft reservation: move from reserved back to available
                    inventory.releaseReservation(reservation.quantity)
                }
                ReservationType.HARD -> {
                    // Release hard allocation: move from allocated back to available
                    inventory.releaseAllocation(reservation.quantity)
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
