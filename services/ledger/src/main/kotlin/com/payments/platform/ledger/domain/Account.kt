package com.payments.platform.ledger.domain

import java.time.Instant
import java.util.UUID

/**
 * Ledger account for double-entry bookkeeping.
 * 
 * Accounts represent economic buckets (not bank accounts):
 * - STRIPE_CLEARING: Money received from Stripe
 * - SELLER_PAYABLE: Money owed to sellers (reference_id = seller_id)
 * - BUYIT_REVENUE: Platform commission
 * - etc.
 */
data class Account(
    val id: UUID,
    val accountType: AccountType,
    val referenceId: String?,  // e.g., seller_id for SELLER_PAYABLE accounts
    val currency: String,
    val createdAt: Instant
)

enum class AccountType {
    STRIPE_CLEARING,
    SELLER_PAYABLE,
    BUYIT_REVENUE,
    FEES_EXPENSE,
    REFUNDS_CLEARING,
    CHARGEBACK_CLEARING,
    BUYER_EXTERNAL  // Logical account, not real balance
}
