package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.Payout
import com.payments.platform.payments.domain.PayoutState
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for Payout.
 * Maps to the payouts table in the database.
 */
@Entity
@Table(name = "payouts")
class PayoutEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "seller_id", nullable = false)
    val sellerId: String,
    
    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    var state: PayoutState, // Use var for state to allow transitions
    
    @Column(name = "stripe_transfer_id", unique = true)
    var stripeTransferId: String?,
    
    @Column(name = "ledger_transaction_id")
    var ledgerTransactionId: UUID?, // Use var for ledgerTransactionId
    
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,
    
    @Column(name = "description")
    val description: String?,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant, // Use var for updatedAt
    
    @Column(name = "completed_at")
    var completedAt: Instant?,
    
    @Column(name = "failure_reason")
    var failureReason: String?
) {
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        sellerId = "",
        amountCents = 0,
        currency = "",
        state = PayoutState.PENDING,
        stripeTransferId = null,
        ledgerTransactionId = null,
        idempotencyKey = "",
        description = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        completedAt = null,
        failureReason = null
    )
    
    fun toDomain(): Payout {
        return Payout(
            id = id,
            sellerId = sellerId,
            amountCents = amountCents,
            currency = currency,
            state = state,
            stripeTransferId = stripeTransferId,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            description = description,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = completedAt,
            failureReason = failureReason
        )
    }
    
    companion object {
        fun fromDomain(payout: Payout): PayoutEntity {
            return PayoutEntity(
                id = payout.id,
                sellerId = payout.sellerId,
                amountCents = payout.amountCents,
                currency = payout.currency,
                state = payout.state,
                stripeTransferId = payout.stripeTransferId,
                ledgerTransactionId = payout.ledgerTransactionId,
                idempotencyKey = payout.idempotencyKey,
                description = payout.description,
                createdAt = payout.createdAt,
                updatedAt = payout.updatedAt,
                completedAt = payout.completedAt,
                failureReason = payout.failureReason
            )
        }
    }
}

