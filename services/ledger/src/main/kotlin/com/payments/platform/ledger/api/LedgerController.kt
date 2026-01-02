package com.payments.platform.ledger.api

import com.payments.platform.ledger.domain.CreateTransactionRequest
import com.payments.platform.ledger.repository.InsufficientFundsException
import com.payments.platform.ledger.service.LedgerService
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
@RequestMapping("/ledger")
@Tag(name = "Ledger", description = "Ledger Service API - System of record for financial transactions")
class LedgerController(
private val ledgerService: LedgerService
) {
    /**
     * POST /ledger/transactions
     * Creates a new ledger transaction.
     * 
     * Enforces:
     * - Atomicity
     * - No overdrafts
     * - Idempotency
     * - Correctness under concurrency
     */
    @Operation(
        summary = "Create a ledger transaction",
        description = "Creates a new transaction in the ledger. Enforces atomicity, no overdrafts, idempotency, and correctness under concurrency using SERIALIZABLE isolation."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Transaction created successfully",
                content = [Content(schema = Schema(implementation = TransactionResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request (validation error, account not found, currency mismatch)",
                content = [Content(schema = Schema(implementation = TransactionResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "422",
                description = "Insufficient funds (overdraft attempt)",
                content = [Content(schema = Schema(implementation = TransactionResponseDto::class))]
            )
        ]
    )
    @PostMapping("/transactions")
    fun createTransaction(
        @Valid @RequestBody request: CreateTransactionRequestDto
    ): ResponseEntity<TransactionResponseDto> {
        return try {
            val transaction = ledgerService.createTransaction(
                CreateTransactionRequest(
                    accountId = request.accountId,
                    amountCents = request.amountCents,
                    currency = request.currency,
                    idempotencyKey = request.idempotencyKey,
                    description = request.description
                )
            )

            ResponseEntity.status(HttpStatus.CREATED).body(
                TransactionResponseDto(
                    transactionId = transaction.transactionId,
                    accountId = transaction.accountId,
                    amountCents = transaction.amountCents,
                    currency = transaction.currency,
                    idempotencyKey = transaction.idempotencyKey,
                    description = transaction.description,
                    createdAt = transaction.createdAt.toString()
                )
            )
        } catch (e: InsufficientFundsException) {
            ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                TransactionResponseDto(
                    error = "Insufficient funds: ${e.message}"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                TransactionResponseDto(
                    error = "Invalid request: ${e.message}"
                )
            )
        }
    }

    /**
     * GET /ledger/accounts/{accountId}/balance
     * Gets the balance for an account.
     * Balance is derived by summing all transactions.
     */
    @Operation(
        summary = "Get account balance",
        description = "Retrieves the current balance for an account by summing all transactions. Safe and explainable under SERIALIZABLE isolation."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Balance retrieved successfully",
                content = [Content(schema = Schema(implementation = BalanceResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Account not found",
                content = [Content(schema = Schema(implementation = BalanceResponseDto::class))]
            )
        ]
    )
    @GetMapping("/accounts/{accountId}/balance")
    fun getBalance(
        @PathVariable accountId: UUID
    ): ResponseEntity<BalanceResponseDto> {
        return try {
            val balance = ledgerService.getBalance(accountId)
            ResponseEntity.ok(
                BalanceResponseDto(
                    accountId = balance.accountId,
                    currency = balance.currency,
                    balanceCents = balance.balanceCents
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                BalanceResponseDto(
                    accountId = accountId,
                    currency = "USD",
                    balanceCents = 0,
                    error = "Account not found: ${e.message}"
                )
            )
        }
    }
}

@Schema(description = "Transaction response")
data class TransactionResponseDto(
    @Schema(description = "Unique transaction ID", example = "660e8400-e29b-41d4-a716-446655440000")
    val transactionId: UUID? = null,
    
    @Schema(description = "The account ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val accountId: UUID? = null,
    
    @Schema(description = "Amount in cents. Positive for credits, negative for debits.", example = "-2500")
    val amountCents: Long? = null,
    
    @Schema(description = "ISO 4217 currency code", example = "USD")
    val currency: String? = null,
    
    @Schema(description = "Idempotency key used for this transaction", example = "refund_abc_123")
    val idempotencyKey: String? = null,
    
    @Schema(description = "Transaction description", example = "Refund for order #123")
    val description: String? = null,
    
    @Schema(description = "Transaction creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null,
    
    @Schema(description = "Error message if the request failed", example = "Insufficient funds: Current balance: 1000 cents, attempted transaction: -2000 cents")
    val error: String? = null
)

