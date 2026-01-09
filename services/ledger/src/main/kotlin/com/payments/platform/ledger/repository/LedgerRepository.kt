package com.payments.platform.ledger.repository

import com.payments.platform.ledger.domain.*
import com.payments.platform.ledger.domain.TransactionWithEntries
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class LedgerRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    /**
     * Creates a double-entry transaction with SERIALIZABLE isolation.
     * 
     * Enforces:
     * - Atomicity (all entries created in single DB transaction)
     * - Balanced transaction (sum of debits = sum of credits)
     * - Idempotency (unique constraint on idempotency_key)
     * - Correctness under concurrency (SERIALIZABLE isolation)
     * - No overdrafts (balance check for accounts that can go negative)
     * 
     * @param request Double-entry transaction request with entries
     * @return Created transaction with all entries
     */
    fun createDoubleEntryTransaction(request: CreateDoubleEntryTransactionRequest): Transaction {
        var lastException: Exception? = null
        val maxRetries = 10
        
        repeat(maxRetries) { attempt ->
            try {
                return createDoubleEntryTransactionInternal(request)
            } catch (e: UnbalancedTransactionException) {
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
                        if (current.sqlState == "40001" || current.message?.contains("serialize") == true) {
                            isSerializationFailure = true
                            break
                        }
                    }
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
                    Thread.sleep((10 * (1 shl attempt)).toLong())
                } else if (isSerializationFailure) {
                    throw RuntimeException("Failed to create transaction after $maxRetries retries due to serialization conflicts", e)
                } else {
                    throw e
                }
            }
        }
        
        throw RuntimeException("Failed to create transaction after $maxRetries retries due to serialization conflicts", lastException)
    }

    /**
     * Internal method that performs the actual double-entry transaction creation.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    private fun createDoubleEntryTransactionInternal(request: CreateDoubleEntryTransactionRequest): Transaction {
        // todo-vh: Is this scalable? Wont it be too slow?
        // Validate entries
        require(request.entries.isNotEmpty()) { "Transaction must have at least one entry" }
        
        // Validate all entries have same currency
        val currency = request.entries.first().currency
        require(request.entries.all { it.currency == currency }) {
            "All entries must have the same currency"
        }
        
        // Validate transaction balances (sum of debits = sum of credits)
        val totalDebits = request.entries
            .filter { it.direction == EntryDirection.DEBIT }
            .sumOf { it.amountCents }
        val totalCredits = request.entries
            .filter { it.direction == EntryDirection.CREDIT }
            .sumOf { it.amountCents }
        
        require(totalDebits == totalCredits) {
            "Transaction must balance. Total debits: $totalDebits, total credits: $totalCredits"
        }
        
        // Check idempotency first
        val existingTransaction = findTransactionByIdempotencyKey(request.idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction
        }
        
        // Validate all accounts exist and have matching currency
        for (entry in request.entries) {
            val account = findAccountById(entry.accountId)
                ?: throw IllegalArgumentException("Account not found: ${entry.accountId}")
            
            if (account.currency != entry.currency) {
                throw IllegalArgumentException(
                    "Entry currency (${entry.currency}) does not match account currency (${account.currency})"
                )
            }
            
            // Validate amount is positive
            require(entry.amountCents > 0) {
                "Entry amount must be positive (direction indicates DEBIT/CREDIT)"
            }
        }
        
        // Generate transaction ID
        val transactionId = UUID.randomUUID()
        
        // Insert transaction metadata
        try {
            jdbcTemplate.update(
                """
                INSERT INTO ledger_transactions (id, reference_id, idempotency_key, description, created_at)
                VALUES (?, ?, ?, ?, now())
                """.trimIndent(),
                transactionId,
                request.referenceId,
                request.idempotencyKey,
                request.description
            )
        } catch (e: DuplicateKeyException) {
            // Idempotency key conflict - fetch and return existing transaction
            val existing = findTransactionByIdempotencyKey(request.idempotencyKey)
                ?: throw IllegalStateException("Idempotency key conflict but transaction not found", e)
            return existing
        } catch (e: DataIntegrityViolationException) {
            throw IllegalArgumentException("Transaction violates constraints: ${e.message}", e)
        }
        
        // Insert all entries atomically
        for (entry in request.entries) {
            val entryId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO ledger_entries 
                    (id, transaction_id, account_id, direction, amount_cents, currency, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """.trimIndent(),
                entryId,
                transactionId,
                entry.accountId,
                entry.direction.name,
                entry.amountCents,
                entry.currency
            )
        }
        
        // Return the created transaction
        return findTransactionById(transactionId)
            ?: throw IllegalStateException("Transaction created but not found")
    }

    /**
     * Gets the balance for an account by summing all entries.
     * 
     * Balance calculation is account-type aware:
     * - For asset accounts (STRIPE_CLEARING, REFUNDS_CLEARING, CHARGEBACK_CLEARING, FEES_EXPENSE): 
     *   DEBIT - CREDIT (positive = money/assets in)
     * - For liability accounts (SELLER_PAYABLE): 
     *   CREDIT - DEBIT (positive = money owed to seller)
     * - For revenue accounts (BUYIT_REVENUE): 
     *   CREDIT - DEBIT (positive = revenue earned)
     * 
     * Safe under SERIALIZABLE isolation - reads see committed transactions only.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE, readOnly = true)
    fun getBalance(accountId: UUID): Balance {
        val account = findAccountById(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        
        // Calculate balance based on account type
        val balanceCents = when (account.accountType) {
            // Asset accounts: DEBIT increases, CREDIT decreases
            com.payments.platform.ledger.domain.AccountType.STRIPE_CLEARING,
            com.payments.platform.ledger.domain.AccountType.REFUNDS_CLEARING,
            com.payments.platform.ledger.domain.AccountType.CHARGEBACK_CLEARING,
            com.payments.platform.ledger.domain.AccountType.FEES_EXPENSE -> {
                // DEBIT - CREDIT
                val sql = """
                    SELECT 
                        COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_cents ELSE 0 END), 0) -
                        COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_cents ELSE 0 END), 0) AS balance_cents
                    FROM ledger_entries
                    WHERE account_id = ?
                """.trimIndent()
                try {
                    jdbcTemplate.queryForObject(sql, Long::class.java, accountId) ?: 0L
                } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
                    0L
                } catch (e: org.springframework.dao.IncorrectResultSizeDataAccessException) {
                    0L
                }
            }
            // Liability and revenue accounts: CREDIT increases, DEBIT decreases
            com.payments.platform.ledger.domain.AccountType.SELLER_PAYABLE,
            com.payments.platform.ledger.domain.AccountType.BUYIT_REVENUE -> {
                // CREDIT - DEBIT
                val sql = """
                    SELECT 
                        COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_cents ELSE 0 END), 0) -
                        COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_cents ELSE 0 END), 0) AS balance_cents
                    FROM ledger_entries
                    WHERE account_id = ?
                """.trimIndent()
                try {
                    jdbcTemplate.queryForObject(sql, Long::class.java, accountId) ?: 0L
                } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
                    0L
                } catch (e: org.springframework.dao.IncorrectResultSizeDataAccessException) {
                    0L
                }
            }
            // Logical accounts don't have real balances
            com.payments.platform.ledger.domain.AccountType.BUYER_EXTERNAL -> {
                0L
            }
        }
        
        return Balance(accountId, account.currency, balanceCents)
    }

    /**
     * Finds an account by ID.
     */
    fun findAccountById(accountId: UUID): Account? {
        val sql = """
            SELECT id, account_type, reference_id, currency, created_at
            FROM ledger_accounts
            WHERE id = ?
        """.trimIndent()
        
        return try {
            jdbcTemplate.queryForObject(sql, accountRowMapper, accountId)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    /**
     * Finds an account by type and reference_id.
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
        val sql = """
            SELECT id, account_type, reference_id, currency, created_at
            FROM ledger_accounts
            WHERE account_type = ? AND reference_id IS NOT DISTINCT FROM ? AND currency = ?
        """.trimIndent()
        
        return try {
            jdbcTemplate.queryForObject(sql, accountRowMapper, accountType.name, referenceId, currency)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    /**
     * Creates a new account in the ledger.
     */
    @Transactional
    fun createAccount(
        accountId: UUID,
        accountType: AccountType,
        referenceId: String?,
        currency: String
    ): Account {
        // Validate currency format
        require(currency.matches(Regex("^[A-Z]{3}$"))) {
            "Currency must be 3 uppercase letters (ISO 4217 format)"
        }

        val sql = """
            INSERT INTO ledger_accounts (id, account_type, reference_id, currency, created_at)
            VALUES (?, ?, ?, ?, now())
        """.trimIndent()

        try {
            jdbcTemplate.update(sql, accountId, accountType.name, referenceId, currency)
        } catch (e: DuplicateKeyException) {
            // Check if it's a unique constraint violation (account_type + reference_id)
            val existing = if (referenceId != null) {
                findAccountByTypeAndReference(accountType, referenceId, currency)
            } else {
                findAccountByTypeAndReference(accountType, null, currency)
            }
            if (existing != null) {
                throw IllegalArgumentException("Account already exists: $accountType with reference_id=$referenceId", e)
            }
            throw IllegalArgumentException("Account already exists: $accountId", e)
        } catch (e: DataIntegrityViolationException) {
            throw IllegalArgumentException("Failed to create account: ${e.message}", e)
        }

        // Fetch the created account
        return findAccountById(accountId)
            ?: throw IllegalStateException("Account created but not found")
    }

    /**
     * Finds all SELLER_PAYABLE accounts with their balances.
     * 
     * Returns a list of seller accounts with their current balances.
     * 
     * @return List of seller account info with balances
     */
    @Transactional(readOnly = true)
    fun findAllSellerAccountsWithBalances(): List<SellerAccountInfo> {
        val sql = """
            SELECT 
                la.id AS account_id,
                la.reference_id AS seller_id,
                la.currency,
                COALESCE(
                    SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount_cents ELSE 0 END) -
                    SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount_cents ELSE 0 END),
                    0
                ) AS balance_cents
            FROM ledger_accounts la
            LEFT JOIN ledger_entries le ON le.account_id = la.id
            WHERE la.account_type = 'SELLER_PAYABLE'
            GROUP BY la.id, la.reference_id, la.currency
            ORDER BY la.reference_id, la.currency
        """.trimIndent()
        
        return jdbcTemplate.query(sql) { rs, _ ->
            com.payments.platform.ledger.domain.SellerAccountInfo(
                accountId = UUID.fromString(rs.getString("account_id")),
                sellerId = rs.getString("seller_id") ?: "",
                currency = rs.getString("currency"),
                balanceCents = rs.getLong("balance_cents")
            )
        }
    }
    
    
    /**
     * Gets all entries for a transaction (for auditing/reconciliation).
     */
    @Transactional(readOnly = true)
    fun getTransactionEntries(transactionId: UUID): List<LedgerEntry> {
        val sql = """
            SELECT id, transaction_id, account_id, direction, amount_cents, currency, created_at
            FROM ledger_entries
            WHERE transaction_id = ?
            ORDER BY created_at
        """.trimIndent()
        
        return jdbcTemplate.query(sql, entryRowMapper, transactionId)
    }

    /**
     * Queries transactions by date range and optional currency filter.
     * 
     * Used for reconciliation to compare ledger transactions with Stripe balance transactions.
     * 
     * @param startDate Start date (inclusive) for filtering transactions
     * @param endDate End date (inclusive) for filtering transactions
     * @param currency Optional currency filter (ISO 4217, uppercase, e.g., "USD")
     * @return List of transactions with their entries, filtered by date range and currency
     */
    @Transactional(readOnly = true)
    fun queryTransactions(
        startDate: Instant,
        endDate: Instant,
        currency: String? = null
    ): List<TransactionWithEntries> {
        // Build SQL query with optional currency filter
        val currencyFilter = if (currency != null) {
            """
            AND EXISTS (
                SELECT 1 FROM ledger_entries le
                INNER JOIN ledger_accounts la ON le.account_id = la.id
                WHERE le.transaction_id = lt.id AND la.currency = ?
            )
            """.trimIndent()
        } else {
            ""
        }
        
        val sql = """
            SELECT DISTINCT lt.id, lt.reference_id, lt.idempotency_key, lt.description, lt.created_at
            FROM ledger_transactions lt
            WHERE lt.created_at >= ? AND lt.created_at <= ?
            $currencyFilter
            ORDER BY lt.created_at
        """.trimIndent()
        
        // Convert Instant to Timestamp for PostgreSQL
        val startTimestamp = Timestamp.from(startDate)
        val endTimestamp = Timestamp.from(endDate)
        
        val params = if (currency != null) {
            listOf(startTimestamp, endTimestamp, currency)
        } else {
            listOf(startTimestamp, endTimestamp)
        }
        
        val transactions = jdbcTemplate.query(sql, transactionRowMapper, *params.toTypedArray())
        
        // For each transaction, fetch its entries
        return transactions.map { transaction ->
            val entries = getTransactionEntries(transaction.id)
            TransactionWithEntries(transaction, entries)
        }
    }

    private fun findTransactionById(transactionId: UUID): Transaction? {
        val sql = """
            SELECT id, reference_id, idempotency_key, description, created_at
            FROM ledger_transactions
            WHERE id = ?
        """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(sql, transactionRowMapper, transactionId)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private fun findTransactionByIdempotencyKey(idempotencyKey: String): Transaction? {
        val sql = """
            SELECT id, reference_id, idempotency_key, description, created_at
            FROM ledger_transactions
            WHERE idempotency_key = ?
        """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(sql, transactionRowMapper, idempotencyKey)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private val accountRowMapper = RowMapper<Account> { rs: ResultSet, _ ->
        Account(
            id = UUID.fromString(rs.getString("id")),
            accountType = AccountType.valueOf(rs.getString("account_type")),
            referenceId = rs.getString("reference_id"),
            currency = rs.getString("currency"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    private val transactionRowMapper = RowMapper<Transaction> { rs: ResultSet, _ ->
        Transaction(
            id = UUID.fromString(rs.getString("id")),
            referenceId = rs.getString("reference_id"),
            idempotencyKey = rs.getString("idempotency_key"),
            description = rs.getString("description"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    private val entryRowMapper = RowMapper<LedgerEntry> { rs: ResultSet, _ ->
        LedgerEntry(
            id = UUID.fromString(rs.getString("id")),
            transactionId = UUID.fromString(rs.getString("transaction_id")),
            accountId = UUID.fromString(rs.getString("account_id")),
            direction = EntryDirection.valueOf(rs.getString("direction")),
            amountCents = rs.getLong("amount_cents"),
            currency = rs.getString("currency"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }
}

/**
 * Exception thrown when a transaction is unbalanced (debits != credits).
 */
class UnbalancedTransactionException(message: String) : RuntimeException(message)
