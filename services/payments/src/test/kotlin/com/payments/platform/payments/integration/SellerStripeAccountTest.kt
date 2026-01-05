package com.payments.platform.payments.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

/**
 * Integration tests for seller Stripe account management.
 * 
 * Tests the internal API endpoints for registering and managing seller Stripe accounts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:postgresql://localhost:5432/payments_test",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "payments.internal.api.token=internal-test-token-change-in-production"
    ]
)
class SellerStripeAccountTest {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @LocalServerPort
    private var port: Int = 0

    private val internalApiToken = "internal-test-token-change-in-production"
    private val sellerId = "test_seller_123"
    private val stripeAccountId = "acct_test_1234567890"

    @BeforeEach
    fun setUp() {
        // Clear seller_stripe_accounts table
        jdbcTemplate.update("DELETE FROM seller_stripe_accounts")
    }

    @Test
    fun `register seller Stripe account - success`() {
        // When: Register seller Stripe account
        val request = mapOf(
            "stripeAccountId" to stripeAccountId,
            "currency" to "USD"
        )
        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
            set("Content-Type", "application/json")
        }

        val response = restTemplate.postForEntity(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts",
            HttpEntity(request, headers),
            Map::class.java
        )

        // Then: API returns 201
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

        // And: Response contains seller account data
        val responseBody = response.body as? Map<*, *>
        assertThat(responseBody?.get("sellerId")).isEqualTo(sellerId)
        assertThat(responseBody?.get("stripeAccountId")).isEqualTo(stripeAccountId)
        assertThat(responseBody?.get("currency")).isEqualTo("USD")

        // And: Account exists in database
        val accountCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM seller_stripe_accounts WHERE seller_id = ? AND currency = ?",
            Int::class.java,
            sellerId,
            "USD"
        ) ?: 0
        assertThat(accountCount).isEqualTo(1)
    }

    @Test
    fun `register seller Stripe account - unauthorized`() {
        // When: Register seller Stripe account without token
        val request = mapOf(
            "stripeAccountId" to stripeAccountId,
            "currency" to "USD"
        )
        val headers = HttpHeaders().apply {
            set("Content-Type", "application/json")
            // No Authorization header
        }

        val response = restTemplate.postForEntity(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts",
            HttpEntity(request, headers),
            Map::class.java
        )

        // Then: API returns 401
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `register seller Stripe account - update existing`() {
        // Given: Seller account already exists
        val initialStripeAccountId = "acct_initial_123"
        val request1 = mapOf(
            "stripeAccountId" to initialStripeAccountId,
            "currency" to "USD"
        )
        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
            set("Content-Type", "application/json")
        }
        restTemplate.postForEntity(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts",
            HttpEntity(request1, headers),
            Map::class.java
        )

        // When: Register with new Stripe account ID
        val request2 = mapOf(
            "stripeAccountId" to stripeAccountId,
            "currency" to "USD"
        )
        val response = restTemplate.postForEntity(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts",
            HttpEntity(request2, headers),
            Map::class.java
        )

        // Then: API returns 200 (updated)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        // And: Account is updated in database
        val account = jdbcTemplate.queryForMap(
            "SELECT * FROM seller_stripe_accounts WHERE seller_id = ? AND currency = ?",
            sellerId,
            "USD"
        )
        assertThat(account["stripe_account_id"]).isEqualTo(stripeAccountId)
    }

    @Test
    fun `get seller Stripe account - success`() {
        // Given: Seller account exists
        val request = mapOf(
            "stripeAccountId" to stripeAccountId,
            "currency" to "USD"
        )
        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
            set("Content-Type", "application/json")
        }
        restTemplate.postForEntity(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts",
            HttpEntity(request, headers),
            Map::class.java
        )

        // When: Get seller Stripe account
        val getHeaders = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
        }
        val response = restTemplate.exchange(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts/USD",
            HttpMethod.GET,
            HttpEntity<Any>(null, getHeaders),
            Map::class.java
        )

        // Then: API returns 200
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        // And: Response contains account data
        val responseBody = response.body as? Map<*, *>
        assertThat(responseBody?.get("sellerId")).isEqualTo(sellerId)
        assertThat(responseBody?.get("stripeAccountId")).isEqualTo(stripeAccountId)
        assertThat(responseBody?.get("currency")).isEqualTo("USD")
    }

    @Test
    fun `get seller Stripe account - not found`() {
        // When: Get non-existent seller account
        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
        }
        val response = restTemplate.exchange(
            "http://localhost:$port/internal/sellers/nonexistent_seller/stripe-accounts/USD",
            HttpMethod.GET,
            HttpEntity<Any>(null, headers),
            Map::class.java
        )

        // Then: API returns 404
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `delete seller Stripe account - success`() {
        // Given: Seller account exists
        val request = mapOf(
            "stripeAccountId" to stripeAccountId,
            "currency" to "USD"
        )
        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
            set("Content-Type", "application/json")
        }
        restTemplate.postForEntity(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts",
            HttpEntity(request, headers),
            Map::class.java
        )

        // When: Delete seller Stripe account
        val deleteHeaders = HttpHeaders().apply {
            set("Authorization", "Bearer $internalApiToken")
        }
        val response = restTemplate.exchange(
            "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts/USD",
            HttpMethod.DELETE,
            HttpEntity<Any>(null, deleteHeaders),
            Map::class.java
        )

        // Then: API returns 200
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        // And: Account is deleted from database
        val accountCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM seller_stripe_accounts WHERE seller_id = ? AND currency = ?",
            Int::class.java,
            sellerId,
            "USD"
        ) ?: 0
        assertThat(accountCount).isEqualTo(0)
    }
}

