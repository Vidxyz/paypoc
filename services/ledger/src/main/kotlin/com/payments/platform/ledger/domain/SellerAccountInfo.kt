package com.payments.platform.ledger.domain

import java.util.UUID

/**
 * Data class for seller account information with balance.
 */
data class SellerAccountInfo(
    val accountId: UUID,
    val sellerId: String,
    val currency: String,
    val balanceCents: Long
)

