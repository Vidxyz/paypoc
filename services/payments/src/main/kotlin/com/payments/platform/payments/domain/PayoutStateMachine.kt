package com.payments.platform.payments.domain

/**
 * Explicit state machine for payout transitions.
 * Enforces valid state transitions as defined in invariants.
 * 
 * This prevents invalid state transitions and makes the state machine
 * explicit and testable.
 */
class PayoutStateMachine {
    
    /**
     * Map of allowed state transitions.
     * Key: current state
     * Value: set of valid next states
     */
    private val allowedTransitions = mapOf(
        PayoutState.PENDING to setOf(PayoutState.PROCESSING, PayoutState.FAILED),
        PayoutState.PROCESSING to setOf(PayoutState.COMPLETED, PayoutState.FAILED),
        PayoutState.COMPLETED to emptySet<PayoutState>(),  // Terminal state
        PayoutState.FAILED to emptySet<PayoutState>()     // Terminal state
    )
    
    /**
     * Validates and performs a state transition.
     * 
     * @param from Current state
     * @param to Desired next state
     * @throws IllegalArgumentException if transition is invalid
     */
    fun transition(from: PayoutState, to: PayoutState) {
        require(allowedTransitions[from]?.contains(to) == true) {
            "Invalid state transition: $from → $to. " +
            "Allowed transitions from $from: ${allowedTransitions[from] ?: "none"}"
        }
    }
    
    /**
     * Checks if a transition is valid without throwing.
     */
    fun isValidTransition(from: PayoutState, to: PayoutState): Boolean {
        return allowedTransitions[from]?.contains(to) == true
    }
    
    /**
     * Gets all valid next states for a given state.
     */
    fun getValidNextStates(from: PayoutState): Set<PayoutState> {
        return allowedTransitions[from] ?: emptySet()
    }
}
