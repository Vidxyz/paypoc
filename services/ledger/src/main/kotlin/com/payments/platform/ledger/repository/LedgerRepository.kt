package com.payments.platform.ledger.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.payments.platform.ledger.domain.Account
import com.payments.platform.ledger.domain.Balance
import com.payments.platform.ledger.domain.Transaction
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.SQLException
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
     * 
     * Retries on serialization failures (expected with SERIALIZABLE isolation).
     */
    fun createTransaction(
        transactionId: UUID,
        accountId: UUID,
        amountCents: Long,
        currency: String,
        idempotencyKey: String,
        description: String?
    ): Transaction {
        var lastException: Exception? = null
        val maxRetries = 10
        
        repeat(maxRetries) { attempt ->
            try {
                return createTransactionInternal(transactionId, accountId, amountCents, currency, idempotencyKey, description)
            } catch (e: InsufficientFundsException) {
                // Don't retry business logic failures
                throw e
            } catch (e: IllegalArgumentException) {
                // Don't retry validation failures
                throw e
            } catch (e: Exception) {
                // Check if this is a serialization failure (SQL state 40001)
                var current: Throwable? = e
                var isSerializationFailure = false
                
                while (current != null) {
                    if (current is SQLException) {
                        // PostgreSQL serialization failure
                        if (current.sqlState == "40001" || current.message?.contains("serialize") == true) {
                            isSerializationFailure = true
                            break
                        }
                    }
                    // Also check for CannotAcquireLockException which Spring wraps serialization failures in
                    if (current is CannotAcquireLockException) {
                        val sqlException = current.cause
                        if (sqlException is SQLException && sqlException.sqlState == "40001") {
                            isSerializationFailure = true
                            break
                        }
                    }
                    current = current.cause
                }
                
                if (isSerializationFailure && attempt < maxRetries - 1) {
                    lastException = e
                    // Exponential backoff: wait 10ms, 20ms, 40ms, 80ms, etc.
                    Thread.sleep((10 * (1 shl attempt)).toLong())
                    // Continue to retry (loop will continue)
                } else if (isSerializationFailure) {
                    // Out of retries
                    throw RuntimeException("Failed to create transaction after $maxRetries retries due to serialization conflicts", e)
                } else {
                    // Not a serialization failure, rethrow immediately
                    throw e
                }
            }
        }
        
        throw RuntimeException("Failed to create transaction after $maxRetries retries due to serialization conflicts", lastException)
    }

    /**
     * Internal method that performs the actual transaction creation.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    private fun createTransactionInternal(
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

        // Validate account exists and currency matches
        val accountCurrency = getAccountCurrency(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        
        if (currency != accountCurrency) {
            throw IllegalArgumentException(
                "Transaction currency ($currency) does not match account currency ($accountCurrency)"
            )
        }

        // Insert transaction with atomic overdraft check
        // Use a single statement that checks balance atomically to prevent race conditions
        // This ensures the balance check and insert happen atomically under SERIALIZABLE isolation
        val sql = """
            INSERT INTO ledger_transactions 
                (transaction_id, account_id, amount_cents, currency, idempotency_key, description, created_at)
            SELECT ?, ?, ?, ?, ?, ?, now()
            WHERE (
                SELECT COALESCE(SUM(amount_cents), 0) + ? 
                FROM ledger_transactions 
                WHERE account_id = ?
            ) >= 0
        """.trimIndent()
        
        // Check if the insert actually happened (returns 1 if inserted, 0 if condition failed)
        val rowsAffected = try {
            jdbcTemplate.update(
                sql,
                transactionId,
                accountId,
                amountCents,
                currency,
                idempotencyKey,
                description,
                amountCents,  // For the WHERE clause check
                accountId     // For the WHERE clause check
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
        
        // If no rows were inserted, it means the overdraft check failed
        if (rowsAffected == 0) {
            // Get the current balance for the error message
            val balanceSql = "SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_transactions WHERE account_id = ?"
            val currentBalanceCents = try {
                jdbcTemplate.queryForObject(balanceSql, Long::class.java, accountId) ?: 0L
            } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
                0L
            } catch (e: org.springframework.dao.IncorrectResultSizeDataAccessException) {
                0L
            }
            throw InsufficientFundsException(
                "Insufficient funds. Current balance: $currentBalanceCents cents, " +
                "attempted transaction: $amountCents cents"
            )
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
        // Get account currency first
        val currency = getAccountCurrency(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        
        // Sum all transactions for this account
        // Note: We don't GROUP BY currency since all transactions for an account must have the same currency
        val sql = """
            SELECT COALESCE(SUM(amount_cents), 0) AS balance_cents
            FROM ledger_transactions
            WHERE account_id = ?
        """.trimIndent()

        val balanceCents = try {
            jdbcTemplate.queryForObject(sql, Long::class.java, accountId) ?: 0L
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            0L
        } catch (e: org.springframework.dao.IncorrectResultSizeDataAccessException) {
            0L
        }
        
        return Balance(accountId, currency, balanceCents)
    }

    private fun getAccountCurrency(accountId: UUID): String? {
        val sql = "SELECT currency FROM ledger_accounts WHERE id = ?"
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

    /**
     * Creates a new account in the ledger.
     * 
     * @param accountId The account ID
     * @param type The account type
     * @param currency The currency code (ISO 4217, 3 uppercase letters)
     * @param status The account status
     * @param metadata Optional metadata as JSON
     * @return The created account
     * @throws IllegalArgumentException if account already exists or currency is invalid
     */
    @Transactional
    fun createAccount(
        accountId: UUID,
        type: com.payments.platform.ledger.domain.AccountType,
        currency: String,
        status: com.payments.platform.ledger.domain.AccountStatus = com.payments.platform.ledger.domain.AccountStatus.ACTIVE,
        metadata: Map<String, Any>? = null
    ): Account {
        // Validate currency format
        require(currency.matches(Regex("^[A-Z]{3}$"))) {
            "Currency must be 3 uppercase letters (ISO 4217 format)"
        }

        val sql = """
            INSERT INTO ledger_accounts (id, type, currency, status, metadata, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, now())
        """.trimIndent()

        try {
            val metadataJson = if (metadata != null) {
                ObjectMapper().writeValueAsString(metadata)
            } else {
                null
            }
            jdbcTemplate.update(sql, accountId, type.name, currency, status.name, metadataJson)
        } catch (e: DuplicateKeyException) {
            throw IllegalArgumentException("Account already exists: $accountId", e)
        } catch (e: DataIntegrityViolationException) {
            throw IllegalArgumentException("Failed to create account: ${e.message}", e)
        }

        // Fetch the created account
        val accountSql = "SELECT id, type, currency, status, metadata, created_at FROM ledger_accounts WHERE id = ?"
        return jdbcTemplate.queryForObject(accountSql, accountRowMapper, accountId)
            ?: throw IllegalStateException("Account created but not found")
    }

    /**
     * Deletes an account and all associated transactions.
     * 
     * Note: This cascades to transactions due to foreign key constraints.
     * 
     * @param accountId The account ID to delete
     * @throws IllegalArgumentException if account does not exist
     */
    @Transactional
    fun deleteAccount(accountId: UUID) {
        // Check if account exists
        val accountExists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_accounts WHERE id = ?",
            Int::class.java,
            accountId
        ) ?: 0

        if (accountExists == 0) {
            throw IllegalArgumentException("Account not found: $accountId")
        }

        // Delete account (transactions will be deleted due to foreign key CASCADE)
        // Note: The foreign key constraint in V3 migration uses ON DELETE RESTRICT,
        // so we need to delete transactions first
        jdbcTemplate.update("DELETE FROM ledger_transactions WHERE account_id = ?", accountId)
        jdbcTemplate.update("DELETE FROM ledger_accounts WHERE id = ?", accountId)
    }

    /**
     * Finds an account by ID.
     */
    fun findAccountById(accountId: UUID): Account? {
        val sql = "SELECT id, type, currency, status, metadata, created_at FROM ledger_accounts WHERE id = ?"
        return try {
            jdbcTemplate.queryForObject(sql, accountRowMapper, accountId)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private val accountRowMapper = RowMapper<Account> { rs: ResultSet, _ ->
        val metadataJson = try {
            rs.getString("metadata")
        } catch (e: Exception) {
            null
        }
        val metadata = if (metadataJson != null && metadataJson.isNotBlank()) {
            try {
                ObjectMapper().readValue(metadataJson, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        Account(
            accountId = UUID.fromString(rs.getString("id")),
            type = com.payments.platform.ledger.domain.AccountType.valueOf(rs.getString("type")),
            currency = rs.getString("currency"),
            status = com.payments.platform.ledger.domain.AccountStatus.valueOf(rs.getString("status")),
            metadata = metadata,
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }
}

/**
 * Exception thrown when a transaction would result in a negative balance (overdraft).
 */
class InsufficientFundsException(message: String) : RuntimeException(message)

