package com.payments.platform.ledger.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:postgresql://localhost:5432/ledger_test",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.hikari.transaction-isolation=8"
    ]
)
class LedgerRepositoryConcurrencyTest {

    @Autowired
    private lateinit var ledgerRepository: LedgerRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var accountId: UUID

    companion object {
        private const val TEST_DB_NAME = "ledger_test"
        private const val DB_HOST = "localhost"
        private const val DB_PORT = 5432
        private val DB_USER = System.getenv("DB_USERNAME") ?: "postgres"
        private val DB_PASSWORD = System.getenv("DB_PASSWORD") ?: "postgres"
        private const val POSTGRES_DB = "postgres" // Default database to connect to for admin operations

        @JvmStatic
        @BeforeAll
        fun setUpDatabase() {
            // Connect to postgres database to create test database
            val adminUrl = "jdbc:postgresql://$DB_HOST:$DB_PORT/$POSTGRES_DB"
            DriverManager.getConnection(adminUrl, DB_USER, DB_PASSWORD).use { connection ->
                try {
                    // Terminate any existing connections to the test database
                    // Note: This must be done outside a transaction
                    connection.autoCommit = true
                    val terminateSql = """
                        SELECT pg_terminate_backend(pg_stat_activity.pid)
                        FROM pg_stat_activity
                        WHERE pg_stat_activity.datname = '$TEST_DB_NAME'
                        AND pid <> pg_backend_pid()
                    """.trimIndent()
                    try {
                        connection.createStatement().execute(terminateSql)
                    } catch (e: Exception) {
                        // Ignore if no connections exist
                    }
                    
                    // Drop database if it exists (must be outside transaction)
                    try {
                        connection.createStatement().executeUpdate("DROP DATABASE IF EXISTS $TEST_DB_NAME")
                    } catch (e: Exception) {
                        // Ignore if database doesn't exist
                    }
                    
                    // Create fresh test database (must be outside transaction)
                    connection.createStatement().executeUpdate("CREATE DATABASE $TEST_DB_NAME")
                    println("Created test database: $TEST_DB_NAME")
                } catch (e: Exception) {
                    throw RuntimeException("Failed to create test database", e)
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDownDatabase() {
            // Connect to postgres database to drop test database
            val adminUrl = "jdbc:postgresql://$DB_HOST:$DB_PORT/$POSTGRES_DB"
            try {
                DriverManager.getConnection(adminUrl, DB_USER, DB_PASSWORD).use { connection ->
                    connection.autoCommit = true
                    try {
                        // Terminate any existing connections to the test database
                        val terminateSql = """
                            SELECT pg_terminate_backend(pg_stat_activity.pid)
                            FROM pg_stat_activity
                            WHERE pg_stat_activity.datname = '$TEST_DB_NAME'
                            AND pid <> pg_backend_pid()
                        """.trimIndent()
                        try {
                            connection.createStatement().execute(terminateSql)
                        } catch (e: Exception) {
                            // Ignore if no connections exist
                        }
                        
                        // Drop test database (must be outside transaction)
                        connection.createStatement().executeUpdate("DROP DATABASE IF EXISTS $TEST_DB_NAME")
                        println("Dropped test database: $TEST_DB_NAME")
                    } catch (e: Exception) {
                        println("Warning: Failed to drop test database: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                println("Warning: Failed to connect to drop test database: ${e.message}")
            }
        }
    }

    @BeforeEach
    fun setUp() {
        // Create test account
        accountId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO ledger_accounts (id, type, currency, status) VALUES (?, ?, ?, ?)",
            accountId,
            "CUSTOMER",
            "USD",
            "ACTIVE"
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
        val exceptions = mutableListOf<Exception>()

        // Each thread will create transactions that sum to zero (balanced)
        val time = measureTimeMillis {
            repeat(numThreads) { threadIndex ->
                executor.submit {
                    try {
                        repeat(transactionsPerThread) { i ->
                            val idempotencyKey = "test_${threadIndex}_$i"
                            
                            try {
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
                            } catch (e: Exception) {
                                synchronized(exceptions) {
                                    exceptions.add(e)
                                }
                                failureCount.incrementAndGet()
                                e.printStackTrace()
                            }
                        }
                    } catch (e: Exception) {
                        synchronized(exceptions) {
                            exceptions.add(e)
                        }
                        e.printStackTrace()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()
        }

        // Wait a bit to ensure all transactions are committed
        Thread.sleep(100)

        // Verify final balance
        val finalBalance = ledgerRepository.getBalance(accountId)
        
        // Each thread: 10 credits of 1000 + 10 debits of -500 = 10 * 500 = 5000 cents
        // 10 threads: 10 * 5000 = 50000 cents
        val expectedTransactions = numThreads * transactionsPerThread
        val expectedBalance = successCount.get() * 500L  // Each successful pair adds 500 cents
        
        println("Concurrency test completed in ${time}ms")
        println("Success: ${successCount.get()}, Failures: ${failureCount.get()} out of $expectedTransactions expected")
        if (exceptions.isNotEmpty()) {
            println("Exceptions encountered: ${exceptions.size}")
            exceptions.take(5).forEach { println("  - ${it.javaClass.simpleName}: ${it.message}") }
        }
        println("Final balance: ${finalBalance.balanceCents} cents (expected: $expectedBalance based on $successCount successful transaction pairs)")
        
        assertThat(finalBalance.balanceCents).isEqualTo(expectedBalance)
        assertThat(successCount.get()).isEqualTo(expectedTransactions)
        assertThat(failureCount.get()).isEqualTo(0)
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
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val exceptions = mutableListOf<Exception>()

        // Writers create transactions
        repeat(numWriters) { writerIndex ->
            executor.submit {
                try {
                    repeat(transactionsPerWriter) { i ->
                        try {
                            ledgerRepository.createTransaction(
                                transactionId = UUID.randomUUID(),
                                accountId = accountId,
                                amountCents = 100L,
                                currency = "USD",
                                idempotencyKey = "writer_${writerIndex}_$i",
                                description = "Write $i"
                            )
                            successCount.incrementAndGet()
                        } catch (e: Exception) {
                            synchronized(exceptions) {
                                exceptions.add(e)
                            }
                            failureCount.incrementAndGet()
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    synchronized(exceptions) {
                        exceptions.add(e)
                    }
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

        // Wait a bit to ensure all transactions are committed
        Thread.sleep(100)

        // All balances should be non-negative and consistent
        // (readers may see different states, but all should be valid)
        assertThat(balances).allMatch { it >= 0 }
        
        // Check how many transactions actually succeeded
        val expectedTransactions = numWriters * transactionsPerWriter
        println("Balance consistency test: ${successCount.get()} succeeded, ${failureCount.get()} failed out of $expectedTransactions expected")
        if (exceptions.isNotEmpty()) {
            println("Exceptions encountered: ${exceptions.size}")
            exceptions.take(5).forEach { println("  - ${it.javaClass.simpleName}: ${it.message}") }
        }
        
        val finalBalance = ledgerRepository.getBalance(accountId)
        val expectedBalance = successCount.get() * 100L
        println("Final balance: ${finalBalance.balanceCents} cents (expected: $expectedBalance based on $successCount successful transactions)")
        
        assertThat(finalBalance.balanceCents).isEqualTo(expectedBalance)
    }
}

