package com.payments.platform.payments.domain

/**
 * Chargeback state enum representing the workflow states.
 * State transitions are enforced by the ChargebackStateMachine.
 */
enum class ChargebackState {
    DISPUTE_CREATED,      // charge.dispute.created received
    NEEDS_RESPONSE,       // Must submit evidence
    UNDER_REVIEW,         // Evidence submitted, being reviewed
    WON,                  // Platform won dispute
    LOST,                 // Platform lost dispute
    WITHDRAWN,            // Buyer withdrew dispute (rare)
    WARNING_CLOSED        // Dispute closed with warning (money returned, but warning recorded)
}

/**
 * Chargeback outcome enum for final resolution.
 */
enum class ChargebackOutcome {
    WON,
    LOST,
    WITHDRAWN,
    WARNING_CLOSED
}

