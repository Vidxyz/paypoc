package com.payments.platform.payments.api

import com.payments.platform.payments.service.ChargebackService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * REST controller for chargeback operations.
 * 
 * Provides endpoints for viewing chargebacks.
 */
@RestController
@RequestMapping("/chargebacks")
@Tag(name = "Chargebacks", description = "Chargeback (dispute) management endpoints")
class ChargebackController(
    private val chargebackService: ChargebackService
) {
    
    /**
     * GET /chargebacks/{chargebackId}
     * 
     * Gets a chargeback by ID.
     */
    @GetMapping("/{chargebackId}")
    @Operation(summary = "Get chargeback by ID", description = "Retrieves chargeback details by chargeback ID")
    fun getChargeback(@PathVariable chargebackId: UUID): ResponseEntity<com.payments.platform.payments.domain.Chargeback> {
        val chargeback = chargebackService.getChargeback(chargebackId)
        return ResponseEntity.ok(chargeback)
    }
    
    /**
     * GET /chargebacks/payments/{paymentId}
     * 
     * Gets all chargebacks for a payment.
     */
    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Get chargebacks for payment", description = "Retrieves all chargebacks associated with a payment")
    fun getChargebacksByPaymentId(@PathVariable paymentId: UUID): ResponseEntity<List<com.payments.platform.payments.domain.Chargeback>> {
        val chargebacks = chargebackService.getChargebacksByPaymentId(paymentId)
        return ResponseEntity.ok(chargebacks)
    }
}

