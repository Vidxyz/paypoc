package com.payments.platform.payments.domain

/**
 * Explicit state machine for chargeback transitions.
 * Enforces valid state transitions as defined in invariants.
 * 
 * This prevents invalid state transitions and makes the state machine
 * explicit and testable.
 */
class ChargebackStateMachine {
    
    /**
     * Map of allowed state transitions.
     * Key: current state
     * Value: set of valid next states
     */
    private val allowedTransitions = mapOf(
        ChargebackState.DISPUTE_CREATED to setOf(ChargebackState.NEEDS_RESPONSE, ChargebackState.WON, ChargebackState.LOST, ChargebackState.WITHDRAWN, ChargebackState.WARNING_CLOSED),
        ChargebackState.NEEDS_RESPONSE to setOf(ChargebackState.UNDER_REVIEW, ChargebackState.WON, ChargebackState.LOST, ChargebackState.WITHDRAWN, ChargebackState.WARNING_CLOSED),
        ChargebackState.UNDER_REVIEW to setOf(ChargebackState.WON, ChargebackState.LOST, ChargebackState.WITHDRAWN, ChargebackState.WARNING_CLOSED),
        ChargebackState.WON to emptySet<ChargebackState>(),         // Terminal state
        ChargebackState.LOST to emptySet<ChargebackState>(),       // Terminal state
        ChargebackState.WITHDRAWN to emptySet<ChargebackState>(),  // Terminal state
        ChargebackState.WARNING_CLOSED to emptySet<ChargebackState>() // Terminal state
    )
    
    /**
     * Validates and performs a state transition.
     * 
     * @param from Current state
     * @param to Desired next state
     * @throws IllegalArgumentException if transition is invalid
     */
    fun transition(from: ChargebackState, to: ChargebackState) {
        require(allowedTransitions[from]?.contains(to) == true) {
            "Invalid chargeback state transition: $from → $to. " +
            "Allowed transitions from $from: ${allowedTransitions[from] ?: "none"}"
        }
    }
    
    /**
     * Checks if a transition is valid without throwing.
     */
    fun isValidTransition(from: ChargebackState, to: ChargebackState): Boolean {
        return allowedTransitions[from]?.contains(to) == true
    }
    
    /**
     * Gets all valid next states for a given state.
     */
    fun getValidNextStates(from: ChargebackState): Set<ChargebackState> {
        return allowedTransitions[from] ?: emptySet()
    }
}

