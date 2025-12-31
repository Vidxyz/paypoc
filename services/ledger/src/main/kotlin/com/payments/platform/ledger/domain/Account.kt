package com.payments.platform.ledger.domain

import java.time.Instant
import java.util.UUID

data class Account(
    val accountId: UUID,
    val currency: String,
    val createdAt: Instant
)

data class AccountMetadata(
    val accountId: UUID,
    val userId: String?,
    val userEmail: String?,
    val displayName: String?,
    val metadata: Map<String, Any>?,
    val createdAt: Instant,
    val updatedAt: Instant
)

