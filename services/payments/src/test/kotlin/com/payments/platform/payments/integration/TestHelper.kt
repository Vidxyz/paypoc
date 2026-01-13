package com.payments.platform.payments.integration

import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

/**
 * Test helper utilities for integration tests.
 * 
 * Provides reusable methods for common test setup operations like
 * registering seller Stripe accounts.
 */
object TestHelper {
    
    /**
     * Registers a seller Stripe account via the internal API.
     * 
     * @param restTemplate The TestRestTemplate to use for HTTP requests
     * @param port The server port
     * @param sellerId The seller ID
     * @param stripeAccountId The Stripe connected account ID
     * @param currency The currency code (e.g., "USD")
     * @param internalApiToken The internal API token for authentication
     * @return true if registration was successful, false otherwise
     */
    fun registerSellerStripeAccount(
        restTemplate: TestRestTemplate,
        port: Int,
        sellerId: String,
        stripeAccountId: String,
        currency: String = "CAD",
        internalApiToken: String = "internal-test-token-change-in-production"
    ): Boolean {
        return try {
            val request = mapOf(
                "stripeAccountId" to stripeAccountId,
                "currency" to currency
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
            
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Deletes a seller Stripe account via the internal API.
     * 
     * @param restTemplate The TestRestTemplate to use for HTTP requests
     * @param port The server port
     * @param sellerId The seller ID
     * @param currency The currency code (e.g., "USD")
     * @param internalApiToken The internal API token for authentication
     * @return true if deletion was successful, false otherwise
     */
    fun deleteSellerStripeAccount(
        restTemplate: TestRestTemplate,
        port: Int,
        sellerId: String,
        currency: String = "CAD",
        internalApiToken: String = "internal-test-token-change-in-production"
    ): Boolean {
        return try {
            val headers = HttpHeaders().apply {
                set("Authorization", "Bearer $internalApiToken")
            }
            
            val response = restTemplate.exchange(
                "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts/$currency",
                org.springframework.http.HttpMethod.DELETE,
                HttpEntity<Any>(null, headers),
                Map::class.java
            )
            
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Gets a seller Stripe account via the internal API.
     * 
     * @param restTemplate The TestRestTemplate to use for HTTP requests
     * @param port The server port
     * @param sellerId The seller ID
     * @param currency The currency code (e.g., "USD")
     * @param internalApiToken The internal API token for authentication
     * @return The response body as a Map, or null if not found
     */
    fun getSellerStripeAccount(
        restTemplate: TestRestTemplate,
        port: Int,
        sellerId: String,
        currency: String = "CAD",
        internalApiToken: String = "internal-test-token-change-in-production"
    ): Map<*, *>? {
        return try {
            val headers = HttpHeaders().apply {
                set("Authorization", "Bearer $internalApiToken")
            }
            
            val response = restTemplate.exchange(
                "http://localhost:$port/internal/sellers/$sellerId/stripe-accounts/$currency",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Any>(null, headers),
                Map::class.java
            )
            
            if (response.statusCode == HttpStatus.OK) {
                response.body as? Map<*, *>
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

