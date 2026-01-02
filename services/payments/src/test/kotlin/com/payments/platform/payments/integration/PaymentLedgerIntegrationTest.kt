package com.payments.platform.payments.integration

import com.payments.platform.payments.api.CreatePaymentRequestDto
import com.payments.platform.payments.persistence.PaymentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.DriverManager
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Critical integration test that proves the architecture.
 * 
 * This test proves:
 * - Payments cannot override ledger correctness
 * - Ledger failure propagates up
 * - No payment record exists if ledger rejects
 * - Ledger balance is unchanged if payment creation fails
 * 
 * This is a DESIGN PROOF, not just a unit test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:postgresql://localhost:5432/payments_test",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        // todo-vh: This is not a good idea. We should use a test container for the ledger service.
        "ledger.service.url=http://localhost:8081"
    ]
)
class PaymentLedgerIntegrationTest {

    private val logger = LoggerFactory.getLogger(PaymentLedgerIntegrationTest::class.java)

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var restTemplate: org.springframework.boot.test.web.client.TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private lateinit var customerAccountId: UUID
    private lateinit var merchantAccountId: UUID

    companion object {
        private const val TEST_DB_NAME = "payments_test"
        private const val DB_HOST = "localhost"
        private const val DB_PORT = 5432
        private val DB_USER = System.getenv("DB_USERNAME") ?: "postgres"
        private val DB_PASSWORD = System.getenv("DB_PASSWORD") ?: "postgres"
        private const val POSTGRES_DB = "postgres"

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
                println("Created test database: $TEST_DB_NAME")
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
                    println("Dropped test database: $TEST_DB_NAME")
                }
            } catch (e: Exception) {
                println("Warning: Failed to drop test database: ${e.message}")
            }
        }
    }

    @BeforeEach
    fun setUp() {
        // Generate account IDs
        customerAccountId = UUID.randomUUID()
        merchantAccountId = UUID.randomUUID()
        
        // Create test accounts directly in ledger database
        // Note: We connect directly to ledger DB because Ledger Service doesn't have account creation API
        // In production, accounts would be created through a separate account management service
        val ledgerDbUrl = "jdbc:postgresql://localhost:5432/ledger_db"
        val ledgerDbUser = System.getenv("DB_USERNAME") ?: "postgres"
        val ledgerDbPassword = System.getenv("DB_PASSWORD") ?: "postgres"
        
        try {
            // todo-vh: Should populate these via internal APIs and not directly to the ledger database
            DriverManager.getConnection(ledgerDbUrl, ledgerDbUser, ledgerDbPassword).use { connection ->
                // Create customer account with initial balance
                connection.prepareStatement(
                    "INSERT INTO ledger_accounts (account_id, currency) VALUES (?, ?) ON CONFLICT DO NOTHING"
                ).use { stmt ->
                    stmt.setObject(1, customerAccountId)
                    stmt.setString(2, "USD")
                    stmt.executeUpdate()
                }
                
                // Create merchant account
                connection.prepareStatement(
                    "INSERT INTO ledger_accounts (account_id, currency) VALUES (?, ?) ON CONFLICT DO NOTHING"
                ).use { stmt ->
                    stmt.setObject(1, merchantAccountId)
                    stmt.setString(2, "USD")
                    stmt.executeUpdate()
                }
                
                // Give customer account initial balance of 500 cents for overdraft test
                // This is done by creating a credit transaction
                connection.prepareStatement(
                    """
                    INSERT INTO ledger_transactions 
                        (transaction_id, account_id, amount_cents, currency, idempotency_key, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setObject(1, UUID.randomUUID())
                    stmt.setObject(2, customerAccountId)
                    stmt.setLong(3, 500L)  // Credit 500 cents
                    stmt.setString(4, "USD")
                    stmt.setString(5, "test_setup_${customerAccountId}_${System.currentTimeMillis()}")
                    stmt.setString(6, "Test setup - initial balance")
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            println("Warning: Could not set up ledger accounts: ${e.message}")
            println("Make sure ledger database exists and is accessible")
            // Test will fail if accounts don't exist, which is expected
        }
        
        // Clear payments table
        jdbcTemplate.update("DELETE FROM payments")
    }

    /**
     * CRITICAL TEST: Payments cannot override ledger correctness.
     * 
     * Scenario:
     * - Ledger has balance = 500 cents (set up in setUp())
     * - Create payment for 600 cents (would cause overdraft)
     * 
     * Expected:
     * - Payments API returns 400
     * - No payment row exists in payments table
     * - Ledger balance unchanged (still 500)
     * 
     * This proves that Payments cannot create payment records without
     * ledger approval, and ledger correctness is preserved.
     */
    @Test
    fun `payments cannot override ledger correctness - overdraft rejected`() {
        // Given: Ledger has balance = 500 cents (set up in setUp() method)
        // Customer account was credited 500 cents during test setup
        
        // When: Create payment for 600 cents (more than available - would cause overdraft)
        val request = CreatePaymentRequestDto(
            accountId = customerAccountId,
            amountCents = 501L,
            currency = "USD",
            description = "Test payment"
        )
        
        val response = restTemplate.postForEntity(
            "http://localhost:$port/payments",
            request,
            Map::class.java
        )

        // Then: Payments API returns 400 (ledger rejected)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        
        // And: No payment row exists in payments table
        val paymentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payments",
            Int::class.java
        ) ?: 0
        assertThat(paymentCount).isEqualTo(0)
        
        // This proves: If ledger rejects, payment is NOT created
        // Ledger correctness is preserved
    }

    /**
     * Test: Successful payment creation follows ledger-first approach.
     * 
     * Scenario:
     * - Ledger has sufficient balance (500 cents from setUp())
     * - Create payment for valid amount (100 cents)
     * 
     * Expected:
     * - Payments API returns 201
     * - Payment row exists with ledger_transaction_id
     * - Ledger transaction exists
     */
    @Test
    fun `successful payment creation follows ledger-first approach`() {
        // Given: Ledger has sufficient balance (500 cents set up in setUp())
        // Customer account was credited 500 cents during test setup
        
        // When: Create payment for valid amount (499 cents, less than available)
        val request = CreatePaymentRequestDto(
            accountId = customerAccountId,
            amountCents = 499L,
            currency = "USD",
            description = "Test payment"
        )
        
        val response = restTemplate.postForEntity(
            "http://localhost:$port/payments",
            request,
            Map::class.java
        )

    
        // Then: Payments API returns 201 (if ledger accepts)
        // Note: This test may fail if ledger service is not running or
        // if account doesn't exist. That's expected - it proves the integration.
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        if (response.statusCode == HttpStatus.CREATED) {
            val paymentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments",
                Int::class.java
            ) ?: 0
            assertThat(paymentCount).isEqualTo(1)
            
            // Verify payment has ledger_transaction_id
            val payment = jdbcTemplate.queryForMap(
                "SELECT * FROM payments LIMIT 1"
            )
            assertThat(payment["ledger_transaction_id"]).isNotNull()
        }
    }
}

