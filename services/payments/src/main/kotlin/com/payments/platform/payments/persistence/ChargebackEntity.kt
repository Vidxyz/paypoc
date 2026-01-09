package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.Chargeback
import com.payments.platform.payments.domain.ChargebackOutcome
import com.payments.platform.payments.domain.ChargebackState
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for Chargeback.
 * Maps to the chargebacks table in the database.
 */
@Entity
@Table(name = "chargebacks")
class ChargebackEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    
    @Column(name = "chargeback_amount_cents", nullable = false)
    val chargebackAmountCents: Long,
    
    @Column(name = "dispute_fee_cents", nullable = false)
    val disputeFeeCents: Long,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    var state: ChargebackState, // Use var for state to allow transitions
    
    @Column(name = "stripe_dispute_id", unique = true, nullable = false)
    val stripeDisputeId: String,
    
    @Column(name = "stripe_charge_id", nullable = false)
    val stripeChargeId: String,
    
    @Column(name = "reason")
    val reason: String?,
    
    @Column(name = "evidence_due_by")
    val evidenceDueBy: Instant?,
    
    @Column(name = "ledger_transaction_id")
    var ledgerTransactionId: UUID?, // Use var for ledgerTransactionId
    
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant, // Use var for updatedAt
    
    @Column(name = "closed_at")
    var closedAt: Instant?,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    var outcome: ChargebackOutcome?
) {
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        paymentId = UUID.randomUUID(),
        chargebackAmountCents = 0,
        disputeFeeCents = 0,
        currency = "",
        state = ChargebackState.DISPUTE_CREATED,
        stripeDisputeId = "",
        stripeChargeId = "",
        reason = null,
        evidenceDueBy = null,
        ledgerTransactionId = null,
        idempotencyKey = "",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        closedAt = null,
        outcome = null
    )
    
    fun toDomain(): Chargeback {
        return Chargeback(
            id = id,
            paymentId = paymentId,
            chargebackAmountCents = chargebackAmountCents,
            disputeFeeCents = disputeFeeCents,
            currency = currency,
            state = state,
            stripeDisputeId = stripeDisputeId,
            stripeChargeId = stripeChargeId,
            reason = reason,
            evidenceDueBy = evidenceDueBy,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            updatedAt = updatedAt,
            closedAt = closedAt,
            outcome = outcome
        )
    }
    
    companion object {
        fun fromDomain(chargeback: Chargeback): ChargebackEntity {
            return ChargebackEntity(
                id = chargeback.id,
                paymentId = chargeback.paymentId,
                chargebackAmountCents = chargeback.chargebackAmountCents,
                disputeFeeCents = chargeback.disputeFeeCents,
                currency = chargeback.currency,
                state = chargeback.state,
                stripeDisputeId = chargeback.stripeDisputeId,
                stripeChargeId = chargeback.stripeChargeId,
                reason = chargeback.reason,
                evidenceDueBy = chargeback.evidenceDueBy,
                ledgerTransactionId = chargeback.ledgerTransactionId,
                idempotencyKey = chargeback.idempotencyKey,
                createdAt = chargeback.createdAt,
                updatedAt = chargeback.updatedAt,
                closedAt = chargeback.closedAt,
                outcome = chargeback.outcome
            )
        }
    }
}

