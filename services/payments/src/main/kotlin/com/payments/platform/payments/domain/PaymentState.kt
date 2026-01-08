package com.payments.platform.payments.domain

/**
 * Payment state enum representing the workflow states.
 * State transitions are enforced by the PaymentStateMachine.
 */
enum class PaymentState {
    CREATED,
    CONFIRMING,
    AUTHORIZED,
    CAPTURED,
    REFUNDING,
    REFUNDED,
    FAILED
}

