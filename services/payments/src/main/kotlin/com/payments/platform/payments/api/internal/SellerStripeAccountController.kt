package com.payments.platform.payments.api.internal

import com.payments.platform.payments.persistence.SellerStripeAccountEntity
import com.payments.platform.payments.persistence.SellerStripeAccountRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

/**
 * Internal API for managing seller Stripe account mappings.
 * 
 * This API is intended for use by other services (e.g., Seller Onboarding Service)
 * and requires authentication via an opaque token.
 * 
 * Endpoints:
 * - POST /internal/sellers/{sellerId}/stripe-accounts - Register seller Stripe account
 * - GET /internal/sellers/{sellerId}/stripe-accounts - Get seller's Stripe accounts
 * - DELETE /internal/sellers/{sellerId}/stripe-accounts/{currency} - Remove seller Stripe account
 */
@RestController
@RequestMapping("/internal/sellers")
class SellerStripeAccountController(
    private val sellerStripeAccountRepository: SellerStripeAccountRepository,
    @Value("\${payments.internal.api.token}") private val apiToken: String
) {
    
    /**
     * Validates the internal API token from the Authorization header.
     */
    private fun validateToken(request: HttpServletRequest): Boolean {
        val authHeader = request.getHeader("Authorization")
        return authHeader != null && authHeader == "Bearer $apiToken"
    }
    
    /**
     * POST /internal/sellers/{sellerId}/stripe-accounts
     * Registers a seller's Stripe account for a specific currency.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/{sellerId}/stripe-accounts")
    fun registerSellerStripeAccount(
        @PathVariable sellerId: String,
        @Valid @RequestBody request: RegisterSellerStripeAccountRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerStripeAccountResponse> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SellerStripeAccountResponse(error = "Unauthorized: Invalid or missing token"))
        }
        
        return try {
            // Check if account already exists
            val existing = sellerStripeAccountRepository.findBySellerIdAndCurrency(sellerId, request.currency)
            if (existing != null) {
                // Update existing account
                val updated = SellerStripeAccountEntity(
                    sellerId = existing.sellerId,
                    currency = existing.currency,
                    stripeAccountId = request.stripeAccountId,
                    createdAt = existing.createdAt,
                    updatedAt = Instant.now()
                )
                val saved = sellerStripeAccountRepository.save(updated)
                
                ResponseEntity.ok(
                    SellerStripeAccountResponse(
                        sellerId = saved.sellerId,
                        stripeAccountId = saved.stripeAccountId,
                        currency = saved.currency,
                        createdAt = saved.createdAt.toString(),
                        updatedAt = saved.updatedAt.toString()
                    )
                )
            } else {
                // Create new account
                val entity = SellerStripeAccountEntity(
                    sellerId = sellerId,
                    currency = request.currency,
                    stripeAccountId = request.stripeAccountId,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
                val saved = sellerStripeAccountRepository.save(entity)
                
                ResponseEntity.status(HttpStatus.CREATED).body(
                    SellerStripeAccountResponse(
                        sellerId = saved.sellerId,
                        stripeAccountId = saved.stripeAccountId,
                        currency = saved.currency,
                        createdAt = saved.createdAt.toString(),
                        updatedAt = saved.updatedAt.toString()
                    )
                )
            }
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                SellerStripeAccountResponse(error = "Failed to register seller Stripe account: ${e.message}")
            )
        }
    }
    
    /**
     * GET /internal/sellers/{sellerId}/stripe-accounts
     * Gets all Stripe accounts for a seller (across all currencies).
     * 
     * Requires: Authorization: Bearer {token}
     */
    @GetMapping("/{sellerId}/stripe-accounts")
    fun getSellerStripeAccounts(
        @PathVariable sellerId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<List<SellerStripeAccountResponse>> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(emptyList())
        }
        
        val accounts = sellerStripeAccountRepository.findBySellerId(sellerId)
        val responses = accounts.map { account ->
            SellerStripeAccountResponse(
                sellerId = account.sellerId,
                stripeAccountId = account.stripeAccountId,
                currency = account.currency,
                createdAt = account.createdAt.toString(),
                updatedAt = account.updatedAt.toString()
            )
        }
        
        return ResponseEntity.ok(responses)
    }
    
    /**
     * GET /internal/sellers/{sellerId}/stripe-accounts/{currency}
     * Gets a seller's Stripe account for a specific currency.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @GetMapping("/{sellerId}/stripe-accounts/{currency}")
    fun getSellerStripeAccount(
        @PathVariable sellerId: String,
        @PathVariable currency: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerStripeAccountResponse> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SellerStripeAccountResponse(error = "Unauthorized: Invalid or missing token"))
        }
        
        val account = sellerStripeAccountRepository.findBySellerIdAndCurrency(sellerId, currency)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                SellerStripeAccountResponse(error = "Seller Stripe account not found for seller $sellerId and currency $currency")
            )
        
        return ResponseEntity.ok(
            SellerStripeAccountResponse(
                sellerId = account.sellerId,
                stripeAccountId = account.stripeAccountId,
                currency = account.currency,
                createdAt = account.createdAt.toString(),
                updatedAt = account.updatedAt.toString()
            )
        )
    }
    
    /**
     * DELETE /internal/sellers/{sellerId}/stripe-accounts/{currency}
     * Removes a seller's Stripe account for a specific currency.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @DeleteMapping("/{sellerId}/stripe-accounts/{currency}")
    fun deleteSellerStripeAccount(
        @PathVariable sellerId: String,
        @PathVariable currency: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerStripeAccountResponse> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SellerStripeAccountResponse(error = "Unauthorized: Invalid or missing token"))
        }
        
        val account = sellerStripeAccountRepository.findBySellerIdAndCurrency(sellerId, currency)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                SellerStripeAccountResponse(error = "Seller Stripe account not found for seller $sellerId and currency $currency")
            )
        
        sellerStripeAccountRepository.delete(account)
        
        return ResponseEntity.ok(
            SellerStripeAccountResponse(
                sellerId = account.sellerId,
                stripeAccountId = account.stripeAccountId,
                currency = account.currency,
                message = "Seller Stripe account deleted successfully"
            )
        )
    }
}

data class RegisterSellerStripeAccountRequest(
    val stripeAccountId: String,  // Stripe connected account ID (e.g., acct_1234567890)
    val currency: String  // ISO 4217 currency code (e.g., USD)
)

data class SellerStripeAccountResponse(
    val sellerId: String? = null,
    val stripeAccountId: String? = null,
    val currency: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val message: String? = null,
    val error: String? = null
)

