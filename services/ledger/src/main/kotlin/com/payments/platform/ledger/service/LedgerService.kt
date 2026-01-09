package com.payments.platform.ledger.service

import com.payments.platform.ledger.domain.*
import com.payments.platform.ledger.repository.LedgerRepository
import com.payments.platform.ledger.repository.UnbalancedTransactionException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LedgerService(
    private val ledgerRepository: LedgerRepository
) {
    /**
     * Creates a double-entry ledger transaction.
     * 
     * Enforces all invariants:
     * - Atomicity (via database transaction)
     * - Balanced transaction (sum of debits = sum of credits)
     * - Idempotency (unique constraint on idempotency_key)
     * - Correctness under concurrency (SERIALIZABLE isolation)
     * 
     * @param request Double-entry transaction request
     * @return Created transaction
     * @throws UnbalancedTransactionException if transaction doesn't balance
     */
    fun createDoubleEntryTransaction(request: CreateDoubleEntryTransactionRequest): Transaction {
        return ledgerRepository.createDoubleEntryTransaction(request)
    }

    /**
     * Gets the balance for an account.
     * Balance is derived by summing all entries: DEBIT entries - CREDIT entries
     */
    fun getBalance(accountId: UUID): Balance {
        return ledgerRepository.getBalance(accountId)
    }

    /**
     * Creates a new account in the ledger.
     * 
     * @param accountId The account ID
     * @param accountType The account type
     * @param referenceId Optional reference ID (e.g., seller_id for SELLER_PAYABLE)
     * @param currency The currency code (ISO 4217, 3 uppercase letters)
     * @return The created account
     */
    fun createAccount(
        accountId: UUID,
        accountType: AccountType,
        referenceId: String?,
        currency: String
    ): Account {
        return ledgerRepository.createAccount(accountId, accountType, referenceId, currency)
    }

    /**
     * Finds an account by ID.
     */
    fun findAccountById(accountId: UUID): Account? {
        return ledgerRepository.findAccountById(accountId)
    }

    /**
     * Finds an account by type and reference ID.
     * 
     * @param accountType The account type
     * @param referenceId The reference ID (e.g., seller_id for SELLER_PAYABLE)
     * @param currency The currency
     * @return The account if found, null otherwise
     */
    fun findAccountByTypeAndReference(
        accountType: AccountType,
        referenceId: String?,
        currency: String
    ): Account? {
        return ledgerRepository.findAccountByTypeAndReference(accountType, referenceId, currency)
    }

    /**
     * Gets all entries for a transaction (for auditing/reconciliation).
     */
    fun getTransactionEntries(transactionId: UUID): List<LedgerEntry> {
        return ledgerRepository.getTransactionEntries(transactionId)
    }

    /**
     * Queries transactions by date range and optional currency filter.
     * 
     * Used for reconciliation to compare ledger transactions with Stripe balance transactions.
     * 
     * @param startDate Start date (inclusive) for filtering transactions
     * @param endDate End date (inclusive) for filtering transactions
     * @param currency Optional currency filter (ISO 4217, uppercase, e.g., "USD")
     * @return List of transactions with their entries
     */
    fun queryTransactions(
        startDate: java.time.Instant,
        endDate: java.time.Instant,
        currency: String? = null
    ): List<TransactionWithEntries> {
        return ledgerRepository.queryTransactions(startDate, endDate, currency)
    }
}
