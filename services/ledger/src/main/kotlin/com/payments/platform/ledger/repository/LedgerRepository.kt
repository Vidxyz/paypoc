package com.payments.platform.ledger.repository

import com.payments.platform.ledger.domain.Balance
import com.payments.platform.ledger.domain.Transaction
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class LedgerRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    /**
     * Creates a ledger transaction with SERIALIZABLE isolation.
     * Enforces:
     * - Atomicity (via database transaction)
     * - No overdrafts (balance check)
     * - Idempotency (unique constraint on idempotency_key)
     * - Correctness under concurrency (SERIALIZABLE isolation)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun createTransaction(
        transactionId: UUID,
        accountId: UUID,
        amountCents: Long,
        currency: String,
        idempotencyKey: String,
        description: String?
    ): Transaction {
        // Check idempotency first (will throw DuplicateKeyException if exists)
        val existingTransaction = findTransactionByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction
        }

        // Get current balance to check for overdraft
        val currentBalance = getBalance(accountId)
        
        // Check for overdraft: balance + amount must be >= 0
        // Since amount can be negative (debit), we check: balance + amount >= 0
        val newBalance = currentBalance.balanceCents + amountCents
        if (newBalance < 0) {
            throw InsufficientFundsException(
                "Insufficient funds. Current balance: ${currentBalance.balanceCents} cents, " +
                "attempted transaction: $amountCents cents"
            )
        }

        // Insert transaction
        val sql = """
            INSERT INTO ledger_transactions 
                (transaction_id, account_id, amount_cents, currency, idempotency_key, description, created_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
        """.trimIndent()

        try {
            jdbcTemplate.update(
                sql,
                transactionId,
                accountId,
                amountCents,
                currency,
                idempotencyKey,
                description
            )
        } catch (e: DuplicateKeyException) {
            // Idempotency key conflict - fetch and return existing transaction
            val existing = findTransactionByIdempotencyKey(idempotencyKey)
                ?: throw IllegalStateException("Idempotency key conflict but transaction not found", e)
            return existing
        } catch (e: DataIntegrityViolationException) {
            // Could be foreign key violation, check constraint violation, etc.
            throw IllegalArgumentException("Transaction violates constraints: ${e.message}", e)
        }

        // Return the created transaction
        return findTransactionById(transactionId)
            ?: throw IllegalStateException("Transaction created but not found")
    }

    /**
     * Gets the balance for an account by summing all transactions.
     * Safe under SERIALIZABLE isolation - reads see committed transactions only.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE, readOnly = true)
    fun getBalance(accountId: UUID): Balance {
        val sql = """
            SELECT
                account_id,
                currency,
                COALESCE(SUM(amount_cents), 0) AS balance_cents
            FROM ledger_transactions
            WHERE account_id = ?
            GROUP BY account_id, currency
        """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(sql, balanceRowMapper, accountId)
                ?: Balance(accountId, "USD", 0) // Default if no transactions
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            // No transactions yet - return zero balance
            // We need to get currency from account
            val currency = getAccountCurrency(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")
            Balance(accountId, currency, 0)
        }
    }

    private fun getAccountCurrency(accountId: UUID): String? {
        val sql = "SELECT currency FROM ledger_accounts WHERE account_id = ?"
        return try {
            jdbcTemplate.queryForObject(sql, String::class.java, accountId)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private fun findTransactionById(transactionId: UUID): Transaction? {
        val sql = """
            SELECT transaction_id, account_id, amount_cents, currency, 
                   idempotency_key, description, created_at
            FROM ledger_transactions
            WHERE transaction_id = ?
        """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(sql, transactionRowMapper, transactionId)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private fun findTransactionByIdempotencyKey(idempotencyKey: String): Transaction? {
        val sql = """
            SELECT transaction_id, account_id, amount_cents, currency, 
                   idempotency_key, description, created_at
            FROM ledger_transactions
            WHERE idempotency_key = ?
        """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(sql, transactionRowMapper, idempotencyKey)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private val transactionRowMapper = RowMapper<Transaction> { rs: ResultSet, _ ->
        Transaction(
            transactionId = UUID.fromString(rs.getString("transaction_id")),
            accountId = UUID.fromString(rs.getString("account_id")),
            amountCents = rs.getLong("amount_cents"),
            currency = rs.getString("currency"),
            idempotencyKey = rs.getString("idempotency_key"),
            description = rs.getString("description"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    private val balanceRowMapper = RowMapper<Balance> { rs: ResultSet, _ ->
        Balance(
            accountId = UUID.fromString(rs.getString("account_id")),
            currency = rs.getString("currency"),
            balanceCents = rs.getLong("balance_cents")
        )
    }
}

/**
 * Exception thrown when a transaction would result in a negative balance (overdraft).
 */
class InsufficientFundsException(message: String) : RuntimeException(message)

