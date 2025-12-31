package com.payments.platform.ledger.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@SpringBootTest
@Testcontainers
class LedgerRepositoryConcurrencyTest {

    @Autowired
    private lateinit var ledgerRepository: LedgerRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var accountId: UUID

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
            withDatabaseName("ledger_test")
            withUsername("test")
            withPassword("test")
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @BeforeEach
    fun setUp() {
        // Create test account
        accountId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO ledger_accounts (account_id, currency) VALUES (?, ?)",
            accountId,
            "USD"
        )

        // Clear any existing transactions
        jdbcTemplate.update("DELETE FROM ledger_transactions WHERE account_id = ?", accountId)
    }

    @Test
    fun `concurrent transactions should maintain balance correctness`() {
        val numThreads = 10
        val transactionsPerThread = 10
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        // Each thread will create transactions that sum to zero (balanced)
        val time = measureTimeMillis {
            repeat(numThreads) { threadIndex ->
                executor.submit {
                    try {
                        repeat(transactionsPerThread) { i ->
                            val idempotencyKey = "test_${threadIndex}_$i"
                            
                            // Create a credit transaction
                            ledgerRepository.createTransaction(
                                transactionId = UUID.randomUUID(),
                                accountId = accountId,
                                amountCents = 1000L,
                                currency = "USD",
                                idempotencyKey = "${idempotencyKey}_credit",
                                description = "Credit $i"
                            )

                            // Create a debit transaction
                            ledgerRepository.createTransaction(
                                transactionId = UUID.randomUUID(),
                                accountId = accountId,
                                amountCents = -500L,
                                currency = "USD",
                                idempotencyKey = "${idempotencyKey}_debit",
                                description = "Debit $i"
                            )

                            successCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        failureCount.incrementAndGet()
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()
        }

        // Verify final balance
        val finalBalance = ledgerRepository.getBalance(accountId)
        
        // Each thread: 10 credits of 1000 + 10 debits of -500 = 10 * 500 = 5000 cents
        // 10 threads: 10 * 5000 = 50000 cents
        val expectedBalance = numThreads * transactionsPerThread * 500L
        
        assertThat(finalBalance.balanceCents).isEqualTo(expectedBalance)
        assertThat(successCount.get()).isEqualTo(numThreads * transactionsPerThread)
        assertThat(failureCount.get()).isEqualTo(0)
        
        println("Concurrency test completed in ${time}ms")
        println("Final balance: ${finalBalance.balanceCents} cents (expected: $expectedBalance)")
    }

    @Test
    fun `concurrent idempotent transactions should not create duplicates`() {
        val numThreads = 20
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val idempotencyKey = "idempotent_test_${UUID.randomUUID()}"
        val transactionIds = mutableSetOf<UUID>()

        // All threads try to create the same transaction (same idempotency key)
        repeat(numThreads) {
            executor.submit {
                try {
                    val transaction = ledgerRepository.createTransaction(
                        transactionId = UUID.randomUUID(),
                        accountId = accountId,
                        amountCents = 1000L,
                        currency = "USD",
                        idempotencyKey = idempotencyKey,
                        description = "Idempotent test"
                    )
                    synchronized(transactionIds) {
                        transactionIds.add(transaction.transactionId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Verify only one transaction was created (all should return the same transaction)
        val transactions = jdbcTemplate.query(
            "SELECT transaction_id FROM ledger_transactions WHERE idempotency_key = ?",
            { rs, _ -> UUID.fromString(rs.getString("transaction_id")) },
            idempotencyKey
        )

        assertThat(transactions).hasSize(1)
        assertThat(transactionIds).hasSize(1) // All threads got the same transaction
    }

    @Test
    fun `concurrent overdraft attempts should be rejected`() {
        // Set initial balance to 1000 cents
        ledgerRepository.createTransaction(
            transactionId = UUID.randomUUID(),
            accountId = accountId,
            amountCents = 1000L,
            currency = "USD",
            idempotencyKey = "initial_balance",
            description = "Initial balance"
        )

        val numThreads = 10
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        // All threads try to debit 200 cents (would cause overdraft if both succeed)
        repeat(numThreads) { threadIndex ->
            executor.submit {
                try {
                    ledgerRepository.createTransaction(
                        transactionId = UUID.randomUUID(),
                        accountId = accountId,
                        amountCents = -200L,
                        currency = "USD",
                        idempotencyKey = "overdraft_test_$threadIndex",
                        description = "Overdraft attempt $threadIndex"
                    )
                    successCount.incrementAndGet()
                } catch (e: InsufficientFundsException) {
                    failureCount.incrementAndGet()
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Verify final balance is non-negative
        val finalBalance = ledgerRepository.getBalance(accountId)
        assertThat(finalBalance.balanceCents).isGreaterThanOrEqualTo(0)
        
        // At most 5 transactions should succeed (1000 / 200 = 5)
        assertThat(successCount.get()).isLessThanOrEqualTo(5)
        assertThat(successCount.get() + failureCount.get()).isEqualTo(numThreads)
        
        println("Overdraft test: $successCount succeeded, $failureCount failed")
        println("Final balance: ${finalBalance.balanceCents} cents")
    }

    @Test
    fun `balance query should be consistent under concurrent writes`() {
        val numWriters = 5
        val numReaders = 10
        val transactionsPerWriter = 20
        val executor = Executors.newFixedThreadPool(numWriters + numReaders)
        val latch = CountDownLatch(numWriters + numReaders)
        val balances = mutableListOf<Long>()

        // Writers create transactions
        repeat(numWriters) { writerIndex ->
            executor.submit {
                try {
                    repeat(transactionsPerWriter) { i ->
                        ledgerRepository.createTransaction(
                            transactionId = UUID.randomUUID(),
                            accountId = accountId,
                            amountCents = 100L,
                            currency = "USD",
                            idempotencyKey = "writer_${writerIndex}_$i",
                            description = "Write $i"
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Readers query balance concurrently
        repeat(numReaders) {
            executor.submit {
                try {
                    val balance = ledgerRepository.getBalance(accountId)
                    synchronized(balances) {
                        balances.add(balance.balanceCents)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // All balances should be non-negative and consistent
        // (readers may see different states, but all should be valid)
        assertThat(balances).allMatch { it >= 0 }
        
        val finalBalance = ledgerRepository.getBalance(accountId)
        assertThat(finalBalance.balanceCents).isEqualTo(numWriters * transactionsPerWriter * 100L)
        
        println("Balance consistency test: ${balances.size} reads, final balance: ${finalBalance.balanceCents}")
    }
}

