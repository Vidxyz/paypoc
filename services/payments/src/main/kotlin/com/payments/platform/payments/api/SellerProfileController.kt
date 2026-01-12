package com.payments.platform.payments.api

import com.payments.platform.payments.auth.JwtValidator
import com.payments.platform.payments.models.User
import com.payments.platform.payments.service.SellerService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Public API for seller profile information.
 * 
 * Requires JWT bearer token authentication.
 * Only SELLER accounts can access these endpoints.
 */
@RestController
@RequestMapping("/seller/profile")
@Tag(name = "Seller Profile", description = "Seller profile and Stripe account information")
class SellerProfileController(
    private val sellerService: SellerService,
    private val jwtValidator: JwtValidator
) {
    
    /**
     * GET /seller/profile/stripe-accounts
     * Gets the authenticated seller's Stripe account information.
     * 
     * Requires: Bearer token with SELLER account_type claim
     * Returns: List of Stripe accounts (one per currency) for the seller
     */
    @Operation(
        summary = "Get seller Stripe accounts",
        description = "Gets all Stripe accounts for the authenticated seller. Requires JWT bearer token with SELLER account_type claim.",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved seller Stripe accounts",
            content = [Content(schema = Schema(implementation = SellerStripeAccountResponse::class))]
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Missing, invalid, or expired bearer token"
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden - Only SELLER accounts can access this endpoint"
        )
    )
    @GetMapping("/stripe-accounts")
    fun getSellerStripeAccounts(
        httpRequest: HttpServletRequest
    ): ResponseEntity<List<SellerStripeAccountResponse>> {
        // Extract bearer token
        val authHeader = httpRequest.getHeader("Authorization")
        val token = authHeader?.removePrefix("Bearer ") ?: run {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(emptyList())
        }
        
        // Validate token and extract user info
        val user = jwtValidator.validateAndExtractUser(token)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(emptyList())
        
        // Verify user is a SELLER
        if (user.accountType != User.AccountType.SELLER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(emptyList())
        }
        
        // Use email as seller_id (as per requirement: seller_id = email)
        val sellerId = user.email
        
        // Fetch seller's Stripe accounts via service
        val accounts = sellerService.getSellerStripeAccounts(sellerId)
        val responses: List<SellerStripeAccountResponse> = accounts.map { account ->
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
    
    // todo-vh: Can add endpoint to register more stripe accounts for the same seller for multiple currencies. 
    //          Doing so should create a new ledger account for the seller with new currency. Payouts would have to be handled carefully here 
    /**
     * PUT /seller/profile/stripe-accounts/{currency}
     * Updates the authenticated seller's Stripe account ID for a specific currency.
     * 
     * Requires: Bearer token with SELLER account_type claim
     * Creates a new entry if one doesn't exist for the currency, or updates existing entry.
     */
    @Operation(
        summary = "Update seller Stripe account ID",
        description = "Updates the Stripe account ID for the authenticated seller for a specific currency. Creates a new entry if one doesn't exist. Requires JWT bearer token with SELLER account_type claim.",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Successfully updated seller Stripe account",
            content = [Content(schema = Schema(implementation = SellerStripeAccountResponse::class))]
        ),
        ApiResponse(
            responseCode = "201",
            description = "Successfully created seller Stripe account entry",
            content = [Content(schema = Schema(implementation = SellerStripeAccountResponse::class))]
        ),
        ApiResponse(
            responseCode = "400",
            description = "Bad Request - Invalid Stripe account ID format or missing currency"
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Missing, invalid, or expired bearer token"
        ),
        ApiResponse(
            responseCode = "403",
            description = "Forbidden - Only SELLER accounts can access this endpoint"
        )
    )
    @PutMapping("/stripe-accounts/{currency}")
    fun updateStripeAccount(
        @PathVariable currency: String,
        @RequestBody request: UpdateStripeAccountRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerStripeAccountResponse> {
        // Extract bearer token
        val authHeader = httpRequest.getHeader("Authorization")
        val token = authHeader?.removePrefix("Bearer ") ?: run {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        // Validate token and extract user info
        val user = jwtValidator.validateAndExtractUser(token)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        
        // Verify user is a SELLER
        if (user.accountType != User.AccountType.SELLER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        
        // Use email as seller_id (as per requirement: seller_id = email)
        val sellerId = user.email
        
        // Check if account exists before update (to determine response status)
        val existingAccount = sellerService.getSellerStripeAccounts(sellerId)
            .firstOrNull { it.currency == currency }
        
        // Update or create Stripe account via service
        val account = try {
            sellerService.updateStripeAccount(sellerId, currency, request.stripeAccountId)
        } catch (e: IllegalArgumentException) {
            // Invalid Stripe account ID format
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(SellerStripeAccountResponse(
                    sellerId = sellerId,
                    stripeAccountId = null,
                    currency = currency,
                    createdAt = "",
                    updatedAt = ""
                ))
        }
        
        val response = SellerStripeAccountResponse(
            sellerId = account.sellerId,
            stripeAccountId = account.stripeAccountId,
            currency = account.currency,
            createdAt = account.createdAt.toString(),
            updatedAt = account.updatedAt.toString()
        )
        
        val status = if (existingAccount != null) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(response)
    }
    
}

data class SellerStripeAccountResponse(
    val sellerId: String,
    val stripeAccountId: String?,
    val currency: String,
    val createdAt: String,
    val updatedAt: String
)

data class UpdateStripeAccountRequest(
    val stripeAccountId: String
)

