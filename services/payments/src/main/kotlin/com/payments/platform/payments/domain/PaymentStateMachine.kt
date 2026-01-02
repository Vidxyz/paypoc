package com.payments.platform.payments.domain

/**
 * Explicit state machine for payment transitions.
 * Enforces valid state transitions as defined in invariants.
 * 
 * This prevents invalid state transitions and makes the state machine
 * explicit and testable.
 */
class PaymentStateMachine {
    
    /**
     * Map of allowed state transitions.
     * Key: current state
     * Value: set of valid next states
     */
    // todo-vh: Refund state transitions
    private val allowedTransitions = mapOf(
        PaymentState.CREATED to setOf(PaymentState.CONFIRMING, PaymentState.FAILED),
        PaymentState.CONFIRMING to setOf(PaymentState.AUTHORIZED, PaymentState.FAILED),
        PaymentState.AUTHORIZED to setOf(PaymentState.CAPTURED, PaymentState.FAILED),
        PaymentState.CAPTURED to emptySet<PaymentState>(),  // Terminal state
        PaymentState.FAILED to emptySet<PaymentState>()     // Terminal state
    )
    
    /**
     * Validates and performs a state transition.
     * 
     * @param from Current state
     * @param to Desired next state
     * @throws IllegalArgumentException if transition is invalid
     */
    fun transition(from: PaymentState, to: PaymentState) {
        require(allowedTransitions[from]?.contains(to) == true) {
            "Invalid state transition: $from → $to. " +
            "Allowed transitions from $from: ${allowedTransitions[from] ?: "none"}"
        }
    }
    
    /**
     * Checks if a transition is valid without throwing.
     */
    fun isValidTransition(from: PaymentState, to: PaymentState): Boolean {
        return allowedTransitions[from]?.contains(to) == true
    }
    
    /**
     * Gets all valid next states for a given state.
     */
    fun getValidNextStates(from: PaymentState): Set<PaymentState> {
        return allowedTransitions[from] ?: emptySet()
    }
}

