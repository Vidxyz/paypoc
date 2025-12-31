package com.payments.platform.ledger.domain

import java.time.Instant
import java.util.UUID

data class Transaction(
    val transactionId: UUID,
    val accountId: UUID,
    val amountCents: Long,
    val currency: String,
    val idempotencyKey: String,
    val description: String?,
    val createdAt: Instant
)

data class CreateTransactionRequest(
    val accountId: UUID,
    val amountCents: Long,
    val currency: String,
    val idempotencyKey: String,
    val description: String? = null
)

