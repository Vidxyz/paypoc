package com.payments.platform.ledger.api

import com.fasterxml.jackson.annotation.JsonProperty
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
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
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
                    currency = "CAD",
                    balanceCents = 0,
                    error = "Account not found: ${e.message}"
                )
            )
        }
    }

    /**
     * GET /ledger/transactions
     * Queries transactions by date range and optional currency filter.
     * 
     * Used for reconciliation to compare ledger transactions with external payment provider records.
     * 
     * @param startDate Start date (inclusive) in ISO 8601 format (e.g., "2024-01-15T00:00:00Z")
     * @param endDate End date (inclusive) in ISO 8601 format (e.g., "2024-01-16T00:00:00Z")
     * @param currency Optional currency filter (ISO 4217, uppercase, e.g., "USD")
     * @return List of transactions with their entries
     */
    @Operation(
        summary = "Query transactions by date range",
        description = "Retrieves transactions within a date range, optionally filtered by currency. Used for reconciliation purposes. Returns transactions with their entries."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Transactions retrieved successfully",
                content = [Content(schema = Schema(implementation = TransactionQueryResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid request (e.g., invalid date range)",
                content = [Content(schema = Schema(implementation = TransactionQueryResponseDto::class))]
            )
        ]
    )
    @GetMapping("/transactions")
    fun queryTransactions(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDate: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDate: Instant,
        @RequestParam(required = false) currency: String?
    ): ResponseEntity<TransactionQueryResponseDto> {
        return try {
            // Validate date range
            require(startDate.isBefore(endDate) || startDate == endDate) {
                "Start date must be before or equal to end date"
            }
            
            // Validate currency format if provided
            if (currency != null) {
                require(currency.matches(Regex("^[A-Z]{3}$"))) {
                    "Currency must be 3 uppercase letters (ISO 4217 format)"
                }
            }
            
            val transactions = ledgerService.queryTransactions(startDate, endDate, currency)
            
            ResponseEntity.ok(
                TransactionQueryResponseDto(
                    transactions = transactions.map { transactionWithEntries ->
                        TransactionWithEntriesDto(
                            transaction = TransactionDto(
                                transactionId = transactionWithEntries.transaction.id,
                                referenceId = transactionWithEntries.transaction.referenceId,
                                idempotencyKey = transactionWithEntries.transaction.idempotencyKey,
                                description = transactionWithEntries.transaction.description,
                                createdAt = transactionWithEntries.transaction.createdAt.toString()
                            ),
                            entries = transactionWithEntries.entries.map { entry ->
                                EntryDto(
                                    entryId = entry.id,
                                    accountId = entry.accountId,
                                    direction = entry.direction.name,
                                    amountCents = entry.amountCents,
                                    currency = entry.currency,
                                    createdAt = entry.createdAt.toString()
                                )
                            }
                        )
                    },
                    error = null
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                TransactionQueryResponseDto(
                    transactions = emptyList(),
                    error = "Invalid request: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /ledger/admin/sellers
     * Gets all seller accounts with their balances (admin-only).
     * 
     * Returns a list of all SELLER_PAYABLE accounts with their current balances.
     */
    @Operation(
        summary = "Get all seller accounts with balances (admin)",
        description = "Retrieves all SELLER_PAYABLE accounts with their current balances. Admin-only endpoint. " +
            "Useful for viewing which sellers have pending funds available for payout."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Seller accounts retrieved successfully",
                content = [Content(schema = Schema(implementation = SellersResponseDto::class))]
            )
        ]
    )
    @GetMapping("/admin/sellers")
    fun getAllSellerAccounts(): ResponseEntity<SellersResponseDto> {
        return try {
            val sellers = ledgerService.getAllSellerAccountsWithBalances()
            ResponseEntity.ok(
                SellersResponseDto(
                    sellers = sellers.map { seller ->
                        SellerInfoDto(
                            sellerId = seller.sellerId,
                            currency = seller.currency,
                            accountId = seller.accountId,
                            balanceCents = seller.balanceCents
                        )
                    },
                    error = null
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                SellersResponseDto(
                    error = "Failed to retrieve sellers: ${e.message}"
                )
            )
        }
    }
}

@Schema(description = "Sellers response")
data class SellersResponseDto(
    @JsonProperty("sellers")
    @Schema(description = "List of sellers with balances")
    val sellers: List<SellerInfoDto> = emptyList(),
    
    @JsonProperty("error")
    @Schema(description = "Error message if request failed")
    val error: String? = null
)

@Schema(description = "Seller information with balance")
data class SellerInfoDto(
    @JsonProperty("sellerId")
    @Schema(description = "Seller ID", example = "seller_123")
    val sellerId: String,
    
    @JsonProperty("currency")
    @Schema(description = "Currency code", example = "CAD")
    val currency: String,
    
    @JsonProperty("accountId")
    @Schema(description = "Ledger account ID")
    val accountId: UUID,
    
    @JsonProperty("balanceCents")
    @Schema(description = "Current balance in cents", example = "50000")
    val balanceCents: Long
)

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
    @Schema(description = "Entry ID", example = "770e8400-e29b-41d4-a716-446655440000")
    val entryId: UUID? = null,
    
    @Schema(description = "Account ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val accountId: UUID,
    
    @Schema(description = "Entry direction: DEBIT or CREDIT", example = "DEBIT")
    val direction: String,
    
    @Schema(description = "Amount in cents (always positive)", example = "10000")
    val amountCents: Long,
    
    @Schema(description = "ISO 4217 currency code", example = "CAD")
    val currency: String,
    
    @Schema(description = "Entry creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String? = null
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

@Schema(description = "Transaction query response")
data class TransactionQueryResponseDto(
    @JsonProperty("transactions")
    @Schema(description = "List of transactions with their entries")
    val transactions: List<TransactionWithEntriesDto>,
    
    @JsonProperty("error")
    @Schema(description = "Error message if the request failed")
    val error: String? = null
)

@Schema(description = "Transaction with entries")
data class TransactionWithEntriesDto(
    @JsonProperty("transaction")
    @Schema(description = "Transaction metadata")
    val transaction: TransactionDto,
    
    @JsonProperty("entries")
    @Schema(description = "List of ledger entries for this transaction")
    val entries: List<EntryDto>
)

@Schema(description = "Transaction metadata")
data class TransactionDto(
    @JsonProperty("transactionId")
    @Schema(description = "Unique transaction ID", example = "660e8400-e29b-41d4-a716-446655440000")
    val transactionId: UUID,
    
    @JsonProperty("referenceId")
    @Schema(description = "External reference ID", example = "pi_1234567890")
    val referenceId: String,
    
    @JsonProperty("idempotencyKey")
    @Schema(description = "Idempotency key", example = "payment_abc_123")
    val idempotencyKey: String,
    
    @JsonProperty("description")
    @Schema(description = "Transaction description", example = "Payment capture: $100.00")
    val description: String,
    
    @JsonProperty("createdAt")
    @Schema(description = "Transaction creation timestamp (ISO 8601)", example = "2024-01-15T10:30:00Z")
    val createdAt: String
)
