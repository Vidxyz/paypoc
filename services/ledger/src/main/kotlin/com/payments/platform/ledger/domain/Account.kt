package com.payments.platform.ledger.domain

import java.time.Instant
import java.util.UUID

data class Account(
    val accountId: UUID,  // Maps to 'id' column in database
    val type: AccountType,
    val currency: String,
    val status: AccountStatus,
    val metadata: Map<String, Any>?,
    val createdAt: Instant
)

enum class AccountType {
    CUSTOMER,
    MERCHANT,
    PSP_CLEARING,
    FEE,
    REFUND
}

enum class AccountStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
    CLOSED
}

data class AccountMetadata(
    val accountId: UUID,
    val userId: String?,
    val userEmail: String?,
    val displayName: String?,
    val metadata: Map<String, Any>?,
    val createdAt: Instant,
    val updatedAt: Instant
)

