package com.payments.platform.ledger.service

import com.payments.platform.ledger.domain.Account
import com.payments.platform.ledger.domain.AccountStatus
import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.domain.Balance
import com.payments.platform.ledger.domain.CreateTransactionRequest
import com.payments.platform.ledger.domain.Transaction
import com.payments.platform.ledger.repository.InsufficientFundsException
import com.payments.platform.ledger.repository.LedgerRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LedgerService(
    private val ledgerRepository: LedgerRepository
) {
    /**
     * Creates a ledger transaction.
     * Enforces all invariants:
     * - Atomicity (via database transaction)
     * - No overdrafts (balance check)
     * - Idempotency (unique constraint)
     * - Correctness under concurrency (SERIALIZABLE isolation)
     */
    fun createTransaction(request: CreateTransactionRequest): Transaction {
        // Validate amount is non-zero (database constraint also enforces this)
        require(request.amountCents != 0L) { "Amount must be non-zero" }

        // Validate currency format (database constraint also enforces this)
        require(request.currency.matches(Regex("^[A-Z]{3}$"))) {
            "Currency must be 3 uppercase letters (ISO 4217 format)"
        }

        // Generate transaction ID
        val transactionId = UUID.randomUUID()

        return try {
            ledgerRepository.createTransaction(
                transactionId = transactionId,
                accountId = request.accountId,
                amountCents = request.amountCents,
                currency = request.currency,
                idempotencyKey = request.idempotencyKey,
                description = request.description
            )
        } catch (e: InsufficientFundsException) {
            throw InsufficientFundsException(
                "Cannot create transaction: ${e.message}"
            )
        }
    }

    /**
     * Gets the balance for an account.
     * Balance is derived by summing all transactions for the account.
     */
    fun getBalance(accountId: UUID): Balance {
        return ledgerRepository.getBalance(accountId)
    }

    /**
     * Creates a new account in the ledger.
     * 
     * @param accountId The account ID
     * @param type The account type
     * @param currency The currency code (ISO 4217, 3 uppercase letters)
     * @param status The account status (defaults to ACTIVE)
     * @param metadata Optional metadata as JSON
     * @return The created account
     */
    fun createAccount(
        accountId: UUID,
        type: AccountType,
        currency: String,
        status: AccountStatus = AccountStatus.ACTIVE,
        metadata: Map<String, Any>? = null
    ): Account {
        return ledgerRepository.createAccount(accountId, type, currency, status, metadata)
    }

    /**
     * Deletes an account and all associated transactions.
     * 
     * @param accountId The account ID to delete
     */
    fun deleteAccount(accountId: UUID) {
        ledgerRepository.deleteAccount(accountId)
    }
}

