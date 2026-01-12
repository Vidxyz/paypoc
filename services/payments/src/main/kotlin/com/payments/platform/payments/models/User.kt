package com.payments.platform.payments.models

import java.util.UUID

/**
 * User model extracted from JWT token claims.
 * These claims are added to Auth0 tokens via Auth0 Actions.
 */
data class User(
    val userId: UUID,
    val email: String,
    val auth0UserId: String, // Auth0 sub claim
    val accountType: AccountType,
    val firstname: String? = null,
    val lastname: String? = null,
) {
    enum class AccountType {
        BUYER,
        SELLER,
        ADMIN;

        companion object {
            fun fromString(value: String): AccountType? {
                return values().find { it.name.equals(value, ignoreCase = true) }
            }
        }
    }
}

