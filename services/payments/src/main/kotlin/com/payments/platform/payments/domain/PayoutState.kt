package com.payments.platform.payments.domain

/**
 * Payout state enum representing the workflow states.
 */
enum class PayoutState {
    PENDING,      // Payout created, not yet initiated with Stripe
    PROCESSING,   // Transfer created with Stripe, waiting for completion
    COMPLETED,    // Transfer completed successfully
    FAILED        // Transfer failed
}

