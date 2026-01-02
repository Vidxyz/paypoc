package com.payments.platform.payments.persistence

import com.payments.platform.payments.domain.Payment
import com.payments.platform.payments.domain.PaymentState
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for Payment.
 * Maps to the payments table in the database.
 */
@Entity
@Table(name = "payments")
class PaymentEntity(
    @Id
    @Column(name = "id")
    val id: UUID,
    
    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,
    
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    val state: PaymentState,
    
    @Column(name = "ledger_transaction_id", nullable = false)
    val ledgerTransactionId: UUID,
    
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant
) {
    // JPA requires no-arg constructor
    constructor() : this(
        id = UUID.randomUUID(),
        amountCents = 0,
        currency = "",
        state = PaymentState.CREATED,
        ledgerTransactionId = UUID.randomUUID(),
        idempotencyKey = "",
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
    
    fun toDomain(): Payment {
        return Payment(
            id = id,
            amountCents = amountCents,
            currency = currency,
            state = state,
            ledgerTransactionId = ledgerTransactionId,
            idempotencyKey = idempotencyKey,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
    
    companion object {
        fun fromDomain(payment: Payment): PaymentEntity {
            return PaymentEntity(
                id = payment.id,
                amountCents = payment.amountCents,
                currency = payment.currency,
                state = payment.state,
                ledgerTransactionId = payment.ledgerTransactionId,
                idempotencyKey = payment.idempotencyKey,
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt
            )
        }
    }
}

