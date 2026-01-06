package com.payments.platform.ledger.api

import com.payments.platform.ledger.domain.CreateDoubleEntryTransactionRequest
import com.payments.platform.ledger.domain.EntryDirection
import com.payments.platform.ledger.domain.EntryRequest
import com.payments.platform.ledger.repository.UnbalancedTransactionException
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
     * Creates a new double-entry ledger transaction.
     * 
     * Enforces:
     * - Atomicity (all entries created in single DB transaction)
     * - Balanced transaction (sum of debits = sum of credits)
     * - Idempotency
     * - Correctness under concurrency
     */
    @Operation(
        summary = "Create a double-entry ledger transaction",
        description = "Creates a new double-entry transaction in the ledger. All entries must balance (sum of debits = sum of credits). Enforces atomicity, idempotency, and correctness under concurrency using SERIALIZABLE isolation."
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
                description = "Invalid request (validation error, unbalanced transaction, account not found)",
                content = [Content(schema = Schema(implementation = TransactionResponseDto::class))]
            )
        ]
    )
    @PostMapping("/transactions")
    fun createTransaction(
        @Valid @RequestBody request: CreateDoubleEntryTransactionRequestDto
    ): ResponseEntity<TransactionResponseDto> {
        return try {
            val entryRequests = request.entries.map { entryDto ->
                EntryRequest(
                    accountId = entryDto.accountId,
                    direction = EntryDirection.valueOf(entryDto.direction),
                    amountCents = entryDto.amountCents,
                    currency = entryDto.currency
                )
            }
            
            val transaction = ledgerService.createDoubleEntryTransaction(
                CreateDoubleEntryTransactionRequest(
                    referenceId = request.referenceId,
                    idempotencyKey = request.idempotencyKey,
                    description = request.description,
                    entries = entryRequests
                )
            )

            ResponseEntity.status(HttpStatus.CREATED).body(
                TransactionResponseDto(
                    transactionId = transaction.id,
                    referenceId = transaction.referenceId,
                    idempotencyKey = transaction.idempotencyKey,
                    description = transaction.description,
                    createdAt = transaction.createdAt.toString()
                )
            )
        } catch (e: UnbalancedTransactionException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                TransactionResponseDto(
                    error = "Unbalanced transaction: ${e.message}"
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
     * Balance = sum of DEBIT entries - sum of CREDIT entries
     */
    @Operation(
        summary = "Get account balance",
        description = "Retrieves the current balance for an account. Balance = sum of DEBIT entries - sum of CREDIT entries. Safe and explainable under SERIALIZABLE isolation."
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
                    balanceCents = balance.balanceCents,
                    error = null
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

@Schema(description = "Double-entry transaction request")
data class CreateDoubleEntryTransactionRequestDto(
    @Schema(description = "External reference ID (e.g., Stripe paymentIntent ID)", example = "pi_1234567890")
    val referenceId: String,
    
    @Schema(description = "Idempotency key to prevent duplicate processing", example = "payment_abc_123")
    val idempotencyKey: String,
    
    @Schema(description = "Human-readable description", example = "Payment capture: $100.00")
    val description: String,
    
    @Schema(description = "List of ledger entries (must balance: sum of debits = sum of credits)")
    val entries: List<EntryDto>
)

@Schema(description = "Ledger entry")
data class EntryDto(
    @Schema(description = "Account ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val accountId: UUID,
    
    @Schema(description = "Entry direction: DEBIT or CREDIT", example = "DEBIT")
    val direction: String,
    
    @Schema(description = "Amount in cents (always positive)", example = "10000")
    val amountCents: Long,
    
    @Schema(description = "ISO 4217 currency code", example = "USD")
    val currency: String
)

@Schema(description = "Transaction response")
data class TransactionResponseDto(
    @Schema(description = "Unique transaction ID", example = "660e8400-e29b-41d4-a716-446655440000")
    val transactionId: UUID? = null,
    
    @Schema(description = "External reference ID", example = "pi_1234567890")
    val referenceId: String? = null,
    
    @Schema(description = "Idempotency key used for this transaction", example = "payment_abc_123")
    val idempotencyKey: String? = null,
    
    @Schema(description = "Transaction description", example = "Payment capture: $100.00")
    val description: String? = null,
    
    @Schema(description = "Transaction creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null,
    
    @Schema(description = "Error message if the request failed")
    val error: String? = null
)
