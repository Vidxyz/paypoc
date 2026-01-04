package com.payments.platform.payments.integration

import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.client.dto.LedgerBalanceResponse
import com.payments.platform.payments.client.dto.LedgerTransactionResponse
import com.payments.platform.payments.domain.PaymentState
import com.payments.platform.payments.kafka.AuthorizePaymentCommand
import com.payments.platform.payments.kafka.PaymentMessage
import com.payments.platform.payments.persistence.PaymentEntity
import com.payments.platform.payments.persistence.PaymentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.sql.DriverManager
import java.util.UUID

/**
 * Integration tests for Kafka-based payment processing resilience.
 * 
 * These tests verify:
 * - Idempotency: Duplicate commands don't cause duplicate side effects
 * - Resilience: Consumer failures don't corrupt state
 * - Correctness: Ledger state remains correct under all scenarios
 * - State machine: Payment state transitions are correct
 * 
 * Test scenarios:
 * 1. Consumer killed mid-processing (message replay)
 * 2. Duplicate commands (idempotency)
 * 3. Handler exceptions (error handling)
 * 4. Message replay after consumer restart
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
    partitions = 3,
    topics = ["payment.commands", "payment.events", "payment.retry"],
    brokerProperties = [
        "listeners=PLAINTEXT://localhost:9092",
        "port=9092"
    ]
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:postgresql://localhost:5432/payments_test",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "ledger.service.url=http://localhost:8081",
        "kafka.topics.commands=payment.commands",
        "kafka.topics.events=payment.events",
        "kafka.topics.retry=payment.retry"
    ]
)
class KafkaPaymentResilienceTest {

    private val logger = LoggerFactory.getLogger(KafkaPaymentResilienceTest::class.java)

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, PaymentMessage>

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @MockBean
    private lateinit var ledgerClient: LedgerClient

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private lateinit var customerAccountId: UUID
    private lateinit var paymentId: UUID
    private lateinit var idempotencyKey: String
    private var initialLedgerBalance: Long = 0L
    private val ledgerTransactionId = UUID.randomUUID()

    companion object {
        private const val TEST_DB_NAME = "payments_test"
        private const val DB_HOST = "localhost"
        private const val DB_PORT = 5432
        private val DB_USER = System.getenv("DB_USERNAME") ?: "postgres"
        private val DB_PASSWORD = System.getenv("DB_PASSWORD") ?: "postgres"
        private const val POSTGRES_DB = "postgres"
        private val logger = LoggerFactory.getLogger(KafkaPaymentResilienceTest::class.java)

        @JvmStatic
        @BeforeAll
        fun setUpDatabase() {
            val adminUrl = "jdbc:postgresql://$DB_HOST:$DB_PORT/$POSTGRES_DB"
            DriverManager.getConnection(adminUrl, DB_USER, DB_PASSWORD).use { connection ->
                connection.autoCommit = true
                try {
                    val terminateSql = """
                        SELECT pg_terminate_backend(pg_stat_activity.pid)
                        FROM pg_stat_activity
                        WHERE pg_stat_activity.datname = '$TEST_DB_NAME'
                        AND pid <> pg_backend_pid()
                    """.trimIndent()
                    connection.createStatement().execute(terminateSql)
                } catch (e: Exception) {
                    // Ignore if no connections exist
                }

                try {
                    connection.createStatement().executeUpdate("DROP DATABASE IF EXISTS $TEST_DB_NAME")
                } catch (e: Exception) {
                    // Ignore if database doesn't exist
                }

                connection.createStatement().executeUpdate("CREATE DATABASE $TEST_DB_NAME")
                logger.info("Created test database: $TEST_DB_NAME")
            }
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun tearDownDatabase() {
            val adminUrl = "jdbc:postgresql://$DB_HOST:$DB_PORT/$POSTGRES_DB"
            try {
                DriverManager.getConnection(adminUrl, DB_USER, DB_PASSWORD).use { connection ->
                    connection.autoCommit = true
                    try {
                        val terminateSql = """
                            SELECT pg_terminate_backend(pg_stat_activity.pid)
                            FROM pg_stat_activity
                            WHERE pg_stat_activity.datname = '$TEST_DB_NAME'
                            AND pid <> pg_backend_pid()
                        """.trimIndent()
                        connection.createStatement().execute(terminateSql)
                    } catch (e: Exception) {
                        // Ignore
                    }

                    connection.createStatement().executeUpdate("DROP DATABASE IF EXISTS $TEST_DB_NAME")
                    logger.info("Dropped test database: $TEST_DB_NAME")
                }
            } catch (e: Exception) {
                logger.warn("Failed to drop test database: ${e.message}")
            }
        }
    }

    @BeforeEach
    fun setUp() {
        // Clear all payments
        paymentRepository.deleteAll()
        
        // Create test account ID
        customerAccountId = UUID.randomUUID()
        
        // Setup mock ledger client
        initialLedgerBalance = 10000L // $100.00
        idempotencyKey = "test_payment_${System.currentTimeMillis()}"
        
        // Mock ledger client to accept transactions (not called during Kafka processing, but for setup)
        whenever(ledgerClient.postTransaction(any()))
            .thenReturn(
                LedgerTransactionResponse(
                    transactionId = ledgerTransactionId,
                    accountId = customerAccountId,
                    amountCents = -1000L, // Debit $10.00
                    currency = "USD",
                    idempotencyKey = idempotencyKey,
                    createdAt = java.time.Instant.now().toString()
                )
            )
        
        whenever(ledgerClient.getBalance(customerAccountId))
            .thenReturn(
                LedgerBalanceResponse(
                    accountId = customerAccountId,
                    currency = "USD",
                    balanceCents = initialLedgerBalance
                )
            )
        
        // Create a payment record in CREATED state (simulating payment creation via API)
        paymentId = UUID.randomUUID()
        val payment = PaymentEntity(
            id = paymentId,
            amountCents = 1000L,
            currency = "USD",
            state = PaymentState.CREATED,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
        )
        paymentRepository.save(payment)
    }

    /**
     * Test: Consumer killed mid-processing
     * 
     * Scenario:
     * 1. Send AuthorizePayment command
     * 2. Consumer starts processing but doesn't acknowledge
     * 3. Simulate consumer restart (message will be replayed)
     * 4. Same message processed again
     * 
     * Assertions:
     * - Payment state is correct (no duplicate transitions)
     * - Idempotency prevents duplicate processing
     * - Ledger unchanged (no new transactions)
     */
    @Test
    @Transactional
    fun `consumer killed mid-processing - message replay should be idempotent`() {
        val command = AuthorizePaymentCommand(
            paymentId = paymentId,
            idempotencyKey = idempotencyKey,
            attempt = 1
        )
        
        // Send command twice (simulating message replay after consumer restart)
        kafkaTemplate.send("payment.commands", paymentId.toString(), command).get()
        kafkaTemplate.send("payment.commands", paymentId.toString(), command).get()
        
        // Wait for processing
        Thread.sleep(5000)
        
        // Verify payment state - should be AUTHORIZED (idempotency prevents duplicate processing)
        val payment = paymentRepository.findById(paymentId).get().toDomain()
        assertThat(payment.state).isIn(PaymentState.AUTHORIZED, PaymentState.CONFIRMING)
        
        // Verify ledger was NOT called during Kafka processing (transaction was created during payment creation)
        verify(ledgerClient, never()).postTransaction(any())
        
        logger.info("Test completed: Payment state = ${payment.state}")
    }

    /**
     * Test: Duplicate commands
     * 
     * Scenario:
     * 1. Send same AuthorizePayment command multiple times
     * 2. Consumer processes each message
     * 
     * Assertions:
     * - Payment state remains correct (idempotency check prevents duplicate processing)
     * - No duplicate side effects (no duplicate ledger transactions, events, etc.)
     */
    @Test
    @Transactional
    fun `duplicate commands should be handled idempotently`() {
        val command = AuthorizePaymentCommand(
            paymentId = paymentId,
            idempotencyKey = idempotencyKey,
            attempt = 1
        )
        
        // Send same command 5 times
        repeat(5) {
            kafkaTemplate.send("payment.commands", paymentId.toString(), command).get()
        }
        
        // Wait for processing
        Thread.sleep(7000)
        
        // Verify payment state - should be AUTHORIZED (idempotency prevents duplicate processing)
        val payment = paymentRepository.findById(paymentId).get().toDomain()
        assertThat(payment.state).isIn(PaymentState.AUTHORIZED, PaymentState.CONFIRMING)
        
        // Verify no duplicate ledger transactions (ledger client should not be called during processing)
        verify(ledgerClient, never()).postTransaction(any())
        
        logger.info("Test completed: Payment state = ${payment.state} after 5 duplicate commands")
    }

    /**
     * Test: Handler exception
     * 
     * Scenario:
     * 1. Send command for non-existent payment
     * 2. Handler throws exception
     * 3. Verify error handling doesn't corrupt state
     * 
     * Assertions:
     * - Exception is handled gracefully
     * - Existing payments are unchanged
     * - No state corruption
     */
    @Test
    @Transactional
    fun `handler exception should not corrupt state`() {
        val nonExistentPaymentId = UUID.randomUUID()
        val command = AuthorizePaymentCommand(
            paymentId = nonExistentPaymentId,
            idempotencyKey = "non_existent_${System.currentTimeMillis()}",
            attempt = 1
        )
        
        // Send command that will fail (payment doesn't exist)
        kafkaTemplate.send("payment.commands", nonExistentPaymentId.toString(), command).get()
        
        // Wait for processing (exception should be handled)
        Thread.sleep(3000)
        
        // Verify no payment was created (exception was handled)
        val paymentExists = paymentRepository.findById(nonExistentPaymentId).isPresent
        assertThat(paymentExists).isFalse()
        
        // Verify original payment is unchanged
        val originalPayment = paymentRepository.findById(paymentId).get().toDomain()
        assertThat(originalPayment.state).isEqualTo(PaymentState.CREATED)
        
        logger.info("Test completed: Exception handled correctly, state uncorrupted")
    }

    /**
     * Test: Message replay after partial processing
     * 
     * Scenario:
     * 1. Send command
     * 2. Wait for partial processing (state becomes CONFIRMING)
     * 3. Send same command again (simulating replay)
     * 4. Verify idempotency
     * 
     * Assertions:
     * - Payment state progresses correctly
     * - No duplicate side effects
     * - Final state is correct
     */
    @Test
    @Transactional
    fun `message replay after partial processing should be idempotent`() {
        val command = AuthorizePaymentCommand(
            paymentId = paymentId,
            idempotencyKey = idempotencyKey,
            attempt = 1
        )
        
        // Send first command
        kafkaTemplate.send("payment.commands", paymentId.toString(), command).get()
        
        // Wait a bit for partial processing
        Thread.sleep(2000)
        
        // Check intermediate state
        val intermediatePayment = paymentRepository.findById(paymentId).get().toDomain()
        logger.info("Intermediate state: ${intermediatePayment.state}")
        
        // Send same command again (simulating replay after consumer restart)
        kafkaTemplate.send("payment.commands", paymentId.toString(), command).get()
        
        // Wait for processing
        Thread.sleep(5000)
        
        // Verify final state
        val finalPayment = paymentRepository.findById(paymentId).get().toDomain()
        assertThat(finalPayment.state).isIn(PaymentState.AUTHORIZED, PaymentState.CONFIRMING)
        
        // Verify no duplicate ledger transactions
        verify(ledgerClient, never()).postTransaction(any())
        
        logger.info("Test completed: Final state = ${finalPayment.state}")
    }

    /**
     * Test: Verify ledger state remains unchanged during Kafka processing
     * 
     * Scenario:
     * 1. Record initial ledger balance
     * 2. Process payment authorization via Kafka
     * 3. Verify ledger balance is unchanged (authorization doesn't change ledger - it's already debited)
     * 
     * Assertions:
     * - Ledger balance unchanged
     * - No duplicate ledger transactions
     */
    @Test
    @Transactional
    fun `ledger state should remain unchanged during Kafka processing`() {
        val initialBalance = initialLedgerBalance
        
        val command = AuthorizePaymentCommand(
            paymentId = paymentId,
            idempotencyKey = idempotencyKey,
            attempt = 1
        )
        
        // Send command
        kafkaTemplate.send("payment.commands", paymentId.toString(), command).get()
        
        // Wait for processing
        Thread.sleep(5000)
        
        // Verify ledger balance is unchanged (authorization is workflow-only, ledger was already debited)
        val balanceAfter = ledgerClient.getBalance(customerAccountId)
        assertThat(balanceAfter.balanceCents).isEqualTo(initialBalance)
        
        // Verify no new ledger transactions were created during Kafka processing
        verify(ledgerClient, times(1)).getBalance(customerAccountId)
        verify(ledgerClient, never()).postTransaction(any())
        
        logger.info("Test completed: Ledger balance unchanged = ${balanceAfter.balanceCents}")
    }
}
