package com.payments.platform.payments.api

import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.client.LedgerClientException
import com.payments.platform.payments.config.AuthenticationInterceptor
import com.payments.platform.payments.models.User
import com.payments.platform.payments.service.PaymentCreationException
import com.payments.platform.payments.service.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "Payments Service API - Orchestration layer for payment workflows")
class PaymentController(
    private val paymentService: PaymentService,
    private val ledgerClient: LedgerClient
) {
    
    /**
     * POST /payments
     * 
     * This endpoint has been removed. Payments are now created through the order service
     * using the internal API endpoint POST /internal/payments/order.
     * 
     * All payments must be associated with an order and can include multiple sellers.
     */
    @Operation(
        summary = "Create a payment (removed)",
        description = "This endpoint has been removed. Payments are now created through the order service. Use POST /internal/payments/order for order-based payments."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "410",
                description = "This endpoint has been removed. Use order service to create payments."
            )
        ]
    )
    @PostMapping
    fun createPayment(
        @Valid @RequestBody request: CreatePaymentRequestDto,
        httpRequest: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity<PaymentResponseDto> {
        return ResponseEntity.status(HttpStatus.GONE).body(
            PaymentResponseDto(
                error = "This endpoint has been removed. Payments must be created through the order service using POST /internal/payments/order. All payments must be associated with an order."
            )
        )
    }
    
    /**
     * GET /payments/{paymentId}
     * Gets a payment by ID.
     */
    @Operation(
        summary = "Get payment by ID",
        description = "Retrieves a payment by its unique identifier."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Payment found",
                content = [Content(schema = Schema(implementation = PaymentResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Payment not found",
                content = [Content(schema = Schema(implementation = PaymentResponseDto::class))]
            )
        ]
    )
    @GetMapping("/{paymentId}")
    fun getPayment(@PathVariable paymentId: UUID): ResponseEntity<PaymentResponseDto> {
        return try {
            val payment = paymentService.getPayment(paymentId)
            // Get chargeback info for this payment
            val chargebackInfoMap = paymentService.getChargebackInfoForPayments(listOf(paymentId))
            val chargebackInfo = chargebackInfoMap[paymentId]
            ResponseEntity.ok(PaymentResponseDto.fromDomain(payment, chargebackInfo = chargebackInfo))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                PaymentResponseDto(
                    error = "Payment not found: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /payments
     * Gets payments for the authenticated user.
     * 
     * For BUYER accounts: Returns payments where the user is the buyer
     * For SELLER accounts: Returns payments where the user is the seller (using email as sellerId)
     * 
     * Supports filtering and sorting:
     * - page: Page number (0-indexed, default: 0)
     * - size: Page size (default: 50)
     * - sortBy: Field to sort by (default: "createdAt")
     * - sortDirection: Sort direction - ASC or DESC (default: DESC)
     * 
     * Requires Bearer token authentication. User role determines which payments are returned.
     */
    @Operation(
        summary = "Get payments for authenticated user",
        description = "Retrieves payments for the authenticated user. For BUYER accounts, returns payments where user is the buyer. For SELLER accounts, returns payments where user is the seller (using email as sellerId). Supports pagination and sorting. Requires Bearer token authentication."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Payments retrieved successfully",
                content = [Content(schema = Schema(implementation = ListPaymentsResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid bearer token"
            )
        ]
    )
    @GetMapping
    fun getPayments(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "createdAt") sortBy: String,
        @RequestParam(defaultValue = "DESC") sortDirection: String,
        request: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity<ListPaymentsResponseDto> {
        // Get user from request attribute (set by AuthenticationInterceptor)
        val user = request.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ListPaymentsResponseDto(
                    error = "Unauthorized: user not found in request"
                )
            )
        
        // Validate pagination parameters
        val validPage = if (page < 0) 0 else page
        val validSize = when {
            size < 1 -> 1
            size > 100 -> 100  // Max page size
            else -> size
        }
        
        // Validate sortBy field
        val validSortBy = when (sortBy.lowercase()) {
            "createdat", "created_at" -> "createdAt"
            "updatedat", "updated_at" -> "updatedAt"
            "state" -> "state"
            "grossamountcents", "gross_amount_cents" -> "grossAmountCents"
            else -> "createdAt"  // Default
        }
        
        // Validate sortDirection
        val validSortDirection = when (sortDirection.uppercase()) {
            "ASC" -> "ASC"
            "DESC" -> "DESC"
            else -> "DESC"  // Default
        }
        
        // Get payments based on account type
        val payments = when (user.accountType) {
            User.AccountType.BUYER -> {
                // For buyers, get payments where they are the buyer
                val buyerId = user.userId.toString()
                paymentService.getPaymentsByBuyerId(
                    buyerId = buyerId,
                    page = validPage,
                    size = validSize,
                    sortBy = validSortBy,
                    sortDirection = validSortDirection
                )
            }
            User.AccountType.SELLER -> {
                // For sellers, get payments where they are the seller (using email as sellerId)
                val sellerId = user.email
                paymentService.getPaymentsBySellerId(
                    sellerId = sellerId,
                    page = validPage,
                    size = validSize,
                    sortBy = validSortBy,
                    sortDirection = validSortDirection
                )
            }
            else -> {
                // ADMIN accounts can see all payments, but for now return empty
                // In the future, we might want to add admin-specific logic here
                emptyList()
            }
        }
        
        // Get chargeback info for all payments in a single optimized query
        val paymentIds = payments.map { it.id }
        val chargebackInfoMap = paymentService.getChargebackInfoForPayments(paymentIds)
        
        return ResponseEntity.ok(
            ListPaymentsResponseDto(
                payments = payments.map { payment ->
                    val chargebackInfo = chargebackInfoMap[payment.id]
                    PaymentResponseDto.fromDomain(payment, chargebackInfo = chargebackInfo)
                },
                page = validPage,
                size = validSize,
                total = payments.size
            )
        )
    }
    
    /**
     * GET /payments/balance
     * Gets balance for the authenticated user's account.
     * 
     * Uses the user's UUID from the auth token as the account ID.
     * This is a read-only delegation to the ledger.
     * Payments never computes balances - it always asks the ledger.
     * 
     * For SELLER accounts: Returns balance for their SELLER_PAYABLE account
     * For BUYER accounts: May not have a ledger account (buyers pay the platform, we payout to sellers)
     */
    @Operation(
        summary = "Get account balance for authenticated user",
        description = "Retrieves the balance for the authenticated user's account. The account ID is extracted from the user's UUID in the bearer token. This is a read-only delegation to the Ledger Service. For SELLER accounts, returns their SELLER_PAYABLE balance. Requires Bearer token authentication."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Balance retrieved successfully",
                content = [Content(schema = Schema(implementation = BalanceResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing or invalid bearer token"
            ),
            ApiResponse(
                responseCode = "404",
                description = "Account not found in ledger",
                content = [Content(schema = Schema(implementation = BalanceResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "503",
                description = "Ledger service unavailable",
                content = [Content(schema = Schema(implementation = BalanceResponseDto::class))]
            )
        ]
    )
    @GetMapping("/balance")
    fun getBalance(
        request: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity<BalanceResponseDto> {
        // Get user from request attribute (set by AuthenticationInterceptor)
        val user = request.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                BalanceResponseDto(
                    error = "Unauthorized: user not found in request"
                )
            )
        
        // For SELLER accounts, use deterministic UUID based on email and currency
        // This matches the account ID used in payment processing and account creation
        // For BUYER accounts, they don't have ledger accounts, so return appropriate response
        val accountId = when (user.accountType) {
            User.AccountType.SELLER -> {
                // Use deterministic UUID to match account creation and payment processing
                // Default to CAD for now (can be extended to support multiple currencies)
                val currency = "CAD"
                UUID.nameUUIDFromBytes("SELLER_PAYABLE_${user.email}_$currency".toByteArray())
            }
            User.AccountType.BUYER -> {
                // BUYER accounts don't have ledger accounts
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    BalanceResponseDto(
                        error = "BUYER accounts do not have ledger accounts"
                    )
                )
            }
            User.AccountType.ADMIN -> {
                // ADMIN accounts don't have ledger accounts
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    BalanceResponseDto(
                        error = "ADMIN accounts do not have ledger accounts"
                    )
                )
            }
        }
        
        return try {
            val balance = ledgerClient.getBalance(accountId)
            ResponseEntity.ok(
                BalanceResponseDto(
                    accountId = balance.accountId,
                    currency = balance.currency,
                    balanceCents = balance.balanceCents
                )
            )
        } catch (e: LedgerClientException) {
            // Check if it's a "not found" error (404) vs service unavailable (503)
            if (e.message?.contains("not found", ignoreCase = true) == true) {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    BalanceResponseDto(
                        accountId = accountId,
                        currency = "CAD",
                        balanceCents = 0,
                        error = "Account not found: ${e.message}"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    BalanceResponseDto(
                        error = "Failed to get balance from ledger: ${e.message}"
                    )
                )
            }
        }
    }
}
