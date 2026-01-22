package com.payments.platform.payments.api

import com.payments.platform.payments.config.AuthenticationInterceptor
import com.payments.platform.payments.models.User
import com.payments.platform.payments.service.SellerService
import com.payments.platform.payments.service.PayoutService
import com.payments.platform.payments.service.RefundService
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
    private val payoutService: PayoutService,
    private val refundService: RefundService
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
        // Get authenticated user from request attribute (set by AuthenticationInterceptor)
        val user = httpRequest.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(emptyList())
        
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
        // Get authenticated user from request attribute (set by AuthenticationInterceptor)
        val user = httpRequest.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        
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
    
    /**
     * GET /seller/profile/payouts
     * Gets the authenticated seller's payouts with pagination.
     * 
     * Requires: Bearer token with SELLER account_type claim
     * Returns: Paginated list of payouts for the seller
     * 
     * Query parameters:
     * - page: Page number (0-indexed, default: 0)
     * - size: Page size (default: 20)
     */
    @Operation(
        summary = "Get seller payouts",
        description = "Gets paginated payouts for the authenticated seller. Requires JWT bearer token with SELLER account_type claim. Supports pagination via page and size query parameters.",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved seller payouts",
            content = [Content(schema = Schema(implementation = ListPayoutsResponseDto::class))]
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
    @GetMapping("/payouts")
    fun getSellerPayouts(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ListPayoutsResponseDto> {
        // Get authenticated user from request attribute (set by AuthenticationInterceptor)
        val user = httpRequest.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ListPayoutsResponseDto(error = "Unauthorized: user not authenticated")
            )
        
        // Use email as seller_id (as per requirement: seller_id = email)
        val sellerId = user.email
        
        // Validate pagination parameters
        val validPage = if (page < 0) 0 else page
        val validSize = when {
            size < 1 -> 1
            size > 100 -> 100
            else -> size
        }
        
        // Fetch seller's payouts with pagination
        val (payouts, total) = payoutService.getPayoutsBySellerId(sellerId, validPage, validSize)
        val payoutResponses = payouts.map { PayoutResponseDto.fromDomain(it) }
        val totalPages = if (total > 0) ((total + validSize - 1) / validSize).toInt() else 0
        
        return ResponseEntity.ok(
            ListPayoutsResponseDto(
                payouts = payoutResponses,
                page = validPage,
                size = validSize,
                total = total,
                totalPages = totalPages
            )
        )
    }
    
    /**
     * POST /seller/profile/refunds/batch
     * Gets refunds for multiple payments for the authenticated seller.
     * 
     * This endpoint returns refunds filtered to only include the seller's portion
     * from each payment's seller breakdown. Only refunds where the seller is part
     * of the payment's seller breakdown are returned.
     * 
     * Requires: Bearer token with SELLER account_type claim
     * Returns: Map of payment ID to list of refunds (filtered for this seller)
     */
    @Operation(
        summary = "Get refunds for multiple payments (Seller) - Batch",
        description = "Retrieves refunds for multiple payments for the authenticated seller. Returns a map of payment ID to list of refunds, filtered to only include the seller's portion. Requires JWT bearer token with SELLER account_type claim.",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved seller refunds",
            content = [Content(schema = Schema(implementation = BatchRefundsResponseDto::class))]
        ),
        ApiResponse(
            responseCode = "400",
            description = "Bad Request - Invalid payment IDs list",
            content = [Content(schema = Schema(implementation = BatchRefundsResponseDto::class))]
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
    @PostMapping("/refunds/batch")
    fun getSellerRefundsForPayments(
        @RequestBody request: BatchRefundsRequestDto,
        httpRequest: HttpServletRequest
    ): ResponseEntity<BatchRefundsResponseDto> {
        // Get authenticated user from request attribute (set by AuthenticationInterceptor)
        val user = httpRequest.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                BatchRefundsResponseDto(
                    refundsByPaymentId = emptyMap(),
                    error = "Unauthorized: user not authenticated"
                )
            )
        
        // Use email as seller_id (as per requirement: seller_id = email)
        val sellerId = user.email
        
        return try {
            if (request.paymentIds.isEmpty()) {
                return ResponseEntity.ok(
                    BatchRefundsResponseDto(
                        refundsByPaymentId = emptyMap(),
                        error = null
                    )
                )
            }
            
            // Get all refunds for the requested payments
            val refundsMap = refundService.getRefundsByPaymentIds(request.paymentIds)
            
            // Filter refunds to only include refunds where this seller is part of the seller breakdown
            // Each refund's sellerRefundBreakdown contains per-seller amounts
            val filteredRefundsMap = refundsMap.mapValues { (_, refunds) ->
                refunds.filter { refund ->
                    // Only include refunds where this seller is in the seller breakdown
                    refund.sellerRefundBreakdown?.any { it.sellerId == sellerId } == true
                }.map { RefundResponseDto.fromDomain(it) }
            }.filterValues { it.isNotEmpty() }
            
            val responseMap = filteredRefundsMap.mapKeys { it.key.toString() }
            
            ResponseEntity.ok(
                BatchRefundsResponseDto(
                    refundsByPaymentId = responseMap,
                    error = null
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                BatchRefundsResponseDto(
                    refundsByPaymentId = emptyMap(),
                    error = "Failed to retrieve refunds: ${e.message}"
                )
            )
        }
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

