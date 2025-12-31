package com.payments.platform.ledger.api

import com.payments.platform.ledger.domain.CreateTransactionRequest
import com.payments.platform.ledger.repository.InsufficientFundsException
import com.payments.platform.ledger.service.LedgerService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/ledger")
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

data class TransactionResponseDto(
    val transactionId: UUID? = null,
    val accountId: UUID? = null,
    val amountCents: Long? = null,
    val currency: String? = null,
    val idempotencyKey: String? = null,
    val description: String? = null,
    val createdAt: String? = null,
    val error: String? = null
)

