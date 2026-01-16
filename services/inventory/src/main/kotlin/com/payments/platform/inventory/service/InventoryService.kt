package com.payments.platform.inventory.service

import com.payments.platform.inventory.domain.Inventory
import com.payments.platform.inventory.domain.TransactionType
import com.payments.platform.inventory.kafka.InventoryKafkaProducer
import com.payments.platform.inventory.kafka.StockCreatedEvent
import com.payments.platform.inventory.kafka.StockUpdatedEvent
import com.payments.platform.inventory.persistence.InventoryEntity
import com.payments.platform.inventory.persistence.InventoryRepository
import com.payments.platform.inventory.persistence.InventoryTransactionEntity
import com.payments.platform.inventory.persistence.InventoryTransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.util.UUID

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val transactionRepository: InventoryTransactionRepository,
    private val kafkaProducer: InventoryKafkaProducer
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun createOrUpdateStock(
        productId: UUID,
        sellerId: String,
        sku: String,
        quantity: Int
    ): Inventory {
        return executeWithRetry(maxRetries = 10) {
            val existing = inventoryRepository.findByProductId(productId)
            
            // Calculate delta for transaction recording (0 for new inventory, difference for existing)
            val delta = if (existing == null) quantity else quantity - existing.totalQuantity
            
            val entity = if (existing == null) {
                logger.info("Creating new inventory for product ID: $productId, seller: $sellerId, sku: $sku, quantity: $quantity")
                InventoryEntity(
                    productId = productId,
                    sellerId = sellerId,
                    sku = sku,
                    availableQuantity = quantity,
                    reservedQuantity = 0,
                    allocatedQuantity = 0,
                    totalQuantity = quantity
                )
            } else {
                logger.info("Updating inventory for product ID: $productId from ${existing.totalQuantity} to $quantity")
                
                // Use smart adjustment logic for both increases and decreases
                // This ensures proper handling of reserved/allocated quantities when reducing stock
                val currentInventory = existing.toDomain()
                val updatedInventory = currentInventory.adjustStock(delta)
                
                // Update entity with the adjusted values
                updateEntity(existing, updatedInventory)
                existing
            }
            
            val saved = inventoryRepository.save(entity)
            
            // Record transaction only if there's an actual change (non-zero delta)
            // This avoids violating the non_zero_quantity constraint when creating inventory with 0 quantity
            if (delta != 0) {
                recordTransaction(
                    inventoryId = saved.id,
                    transactionType = if (delta > 0) TransactionType.STOCK_ADD else TransactionType.STOCK_REMOVE,
                    quantity = kotlin.math.abs(delta),
                    description = if (existing == null) "Initial stock" else "Stock adjustment (set to $quantity)"
                )
            }
            
            // Publish event
            if (existing == null) {
                kafkaProducer.publishStockCreatedEvent(StockCreatedEvent(
                    stockId = saved.id,
                    productId = saved.productId,
                    availableQuantity = saved.availableQuantity,
                    totalQuantity = saved.totalQuantity,
                    reservedQuantity = saved.reservedQuantity,
                    allocatedQuantity = saved.allocatedQuantity
                ))
            } else {
                kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                    stockId = saved.id,
                    productId = saved.productId,
                    availableQuantity = saved.availableQuantity,
                    totalQuantity = saved.totalQuantity,
                    reservedQuantity = saved.reservedQuantity,
                    allocatedQuantity = saved.allocatedQuantity
                ))
            }
            
            saved.toDomain()
        }
    }
    
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun adjustStock(
        inventoryId: UUID,
        delta: Int
    ): Inventory {
        return executeWithRetry(maxRetries = 10) {
            val entity = inventoryRepository.findById(inventoryId)
                .orElseThrow { NoSuchElementException("Inventory not found: $inventoryId") }
            
            val inventory = entity.toDomain()
            val updated = inventory.adjustStock(delta)
            
            updateEntity(entity, updated)
            val saved = inventoryRepository.save(entity)
            
            // Record transaction only if there's an actual change (non-zero delta)
            // This avoids violating the non_zero_quantity constraint
            if (delta != 0) {
                recordTransaction(
                    inventoryId = saved.id,
                    transactionType = if (delta > 0) TransactionType.STOCK_ADD else TransactionType.STOCK_REMOVE,
                    quantity = kotlin.math.abs(delta),
                    description = "Stock adjustment"
                )
            }
            
            kafkaProducer.publishStockUpdatedEvent(StockUpdatedEvent(
                stockId = saved.id,
                productId = saved.productId,
                availableQuantity = saved.availableQuantity,
                totalQuantity = saved.totalQuantity,
                reservedQuantity = saved.reservedQuantity,
                allocatedQuantity = saved.allocatedQuantity
            ))
            
            saved.toDomain()
        }
    }
    
    @Transactional(readOnly = true)
    fun getStockByProductId(productId: UUID): Inventory? {
        return inventoryRepository.findByProductId(productId)?.toDomain()
    }
    
    @Transactional(readOnly = true)
    fun getStockById(inventoryId: UUID): Inventory? {
        return inventoryRepository.findById(inventoryId).orElse(null)?.toDomain()
    }
    
    @Transactional(readOnly = true)
    fun getStockBySellerAndSku(sellerId: String, sku: String): Inventory? {
        return inventoryRepository.findBySellerIdAndSku(sellerId, sku)?.toDomain()
    }
    
    @Transactional(readOnly = true)
    fun getStockByProductIds(productIds: List<UUID>): Map<UUID, Inventory> {
        if (productIds.isEmpty()) {
            return emptyMap()
        }
        val entities = inventoryRepository.findByProductIdIn(productIds)
        return entities.associate { it.productId to it.toDomain() }
    }
    
    @Transactional(readOnly = true)
    fun getLowStockItems(): List<Inventory> {
        return inventoryRepository.findLowStockItems().map { it.toDomain() }
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
                    logger.debug("Retrying inventory operation after optimistic locking failure (attempt ${attempt + 1}/$maxRetries, delay ${delay}ms)")
                    Thread.sleep(delay)
                } else if (isRetryable) {
                    throw RuntimeException("Failed inventory operation after $maxRetries retries due to optimistic locking conflicts", e)
                } else {
                    throw e
                }
            }
        }
        
        throw RuntimeException("Failed inventory operation after $maxRetries retries due to optimistic locking conflicts", lastException)
    }
    
    private fun recordTransaction(
        inventoryId: UUID,
        transactionType: TransactionType,
        quantity: Int,
        referenceId: UUID? = null,
        description: String? = null
    ) {
        val transaction = InventoryTransactionEntity(
            inventoryId = inventoryId,
            transactionType = transactionType,
            quantity = quantity,
            referenceId = referenceId,
            description = description
        )
        transactionRepository.save(transaction)
    }
    
    private fun InventoryEntity.toDomain(): Inventory {
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
    
    private fun updateEntity(entity: InventoryEntity, inventory: Inventory) {
        entity.availableQuantity = inventory.availableQuantity
        entity.reservedQuantity = inventory.reservedQuantity
        entity.allocatedQuantity = inventory.allocatedQuantity
        entity.totalQuantity = inventory.totalQuantity
        entity.lowStockThreshold = inventory.lowStockThreshold
    }
}
