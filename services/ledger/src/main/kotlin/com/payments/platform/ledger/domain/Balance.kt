package com.payments.platform.ledger.domain

import java.util.UUID

data class Balance(
    val accountId: UUID,
    val currency: String,
    val balanceCents: Long
)

