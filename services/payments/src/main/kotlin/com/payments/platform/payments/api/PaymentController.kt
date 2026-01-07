package com.payments.platform.payments.api

import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.client.LedgerClientException
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
     * Creates a new payment.
     * 
     * This endpoint orchestrates the payment workflow:
     * 1. Looks up seller's Stripe account from database (based on sellerId and currency)
     * 2. Creates Stripe PaymentIntent with marketplace split
     * 3. Creates payment record (NO ledger write - money hasn't moved yet)
     * 4. Publishes AuthorizePayment command to Kafka
     * 5. Returns payment with client_secret (client doesn't wait for Stripe/Kafka processing)
     * 
     * Ledger write happens AFTER Stripe webhook confirms capture.
     * 
     * Note: The seller's Stripe account must be configured via the internal API before
     * payments can be processed for that seller.
     */
    @Operation(
        summary = "Create a payment",
        description = "Creates a new payment and Stripe PaymentIntent. The seller's Stripe account is automatically looked up from the database based on sellerId and currency. NO ledger write at this stage - money hasn't moved yet. Returns client_secret for frontend payment confirmation. Ledger write happens after Stripe webhook confirms capture."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Payment created successfully",
                content = [Content(schema = Schema(implementation = PaymentResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Payment creation failed (validation error)",
                content = [Content(schema = Schema(implementation = PaymentResponseDto::class))]
            )
        ]
    )
    @PostMapping
    fun createPayment(
        @Valid @RequestBody request: CreatePaymentRequestDto
    ): ResponseEntity<PaymentResponseDto> {
        return try {
            val createPaymentResponse = paymentService.createPayment(
                com.payments.platform.payments.service.CreatePaymentRequest(
                    buyerId = request.buyerId,
                    sellerId = request.sellerId,
                    grossAmountCents = request.grossAmountCents,
                    currency = request.currency,
                    description = request.description
                )
            )
            
            ResponseEntity.status(HttpStatus.CREATED).body(
                PaymentResponseDto.fromDomain(createPaymentResponse.payment, createPaymentResponse.clientSecret)
            )
        } catch (e: com.payments.platform.payments.service.PaymentCreationException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                PaymentResponseDto(
                    error = "Payment creation failed: ${e.message}"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                PaymentResponseDto(
                    error = "Invalid request: ${e.message}"
                )
            )
        }
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
            ResponseEntity.ok(PaymentResponseDto.fromDomain(payment))
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
     * Gets payments for the authenticated user (buyerId from bearer token).
     * 
     * Supports filtering and sorting:
     * - page: Page number (0-indexed, default: 0)
     * - size: Page size (default: 50)
     * - sortBy: Field to sort by (default: "createdAt")
     * - sortDirection: Sort direction - ASC or DESC (default: DESC)
     * 
     * Requires Bearer token authentication. The buyerId is extracted from the token.
     */
    @Operation(
        summary = "Get payments for authenticated user",
        description = "Retrieves payments for the authenticated user (buyerId extracted from bearer token). Supports pagination and sorting. Requires Bearer token authentication."
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
        // Get buyerId from request attribute (set by AuthenticationInterceptor)
        val buyerId = request.getAttribute("buyerId") as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ListPaymentsResponseDto(
                    error = "Unauthorized: buyerId not found in request"
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
        
        val payments = paymentService.getPaymentsByBuyerId(
            buyerId = buyerId,
            page = validPage,
            size = validSize,
            sortBy = validSortBy,
            sortDirection = validSortDirection
        )
        
        return ResponseEntity.ok(
            ListPaymentsResponseDto(
                payments = payments.map { PaymentResponseDto.fromDomain(it) },
                page = validPage,
                size = validSize,
                total = payments.size
            )
        )
    }
    
    /**
     * GET /balance?accountId={accountId}
     * Gets balance for an account.
     * 
     * This is a read-only delegation to the ledger.
     * Payments never computes balances - it always asks the ledger.
     */
    @Operation(
        summary = "Get account balance",
        description = "Retrieves the balance for an account. This is a read-only delegation to the Ledger Service. Payments never computes balances - it always asks the ledger."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Balance retrieved successfully",
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
    fun getBalance(@RequestParam accountId: UUID): ResponseEntity<BalanceResponseDto> {
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
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                BalanceResponseDto(
                    error = "Failed to get balance from ledger: ${e.message}"
                )
            )
        }
    }
}
