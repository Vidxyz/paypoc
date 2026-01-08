package com.payments.platform.payments.api

import com.payments.platform.payments.service.RefundCreationException
import com.payments.platform.payments.service.RefundService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping
@Tag(name = "Refunds", description = "Refund operations for payments")
class RefundController(
    private val refundService: RefundService
) {
    
    /**
     * POST /payments/{paymentId}/refund
     * Creates a full refund for a payment.
     * 
     * This endpoint orchestrates the refund workflow:
     * 1. Validates payment exists and is CAPTURED
     * 2. Validates payment hasn't been refunded already
     * 3. Creates Stripe Refund (full refund)
     * 4. Creates refund record (NO ledger write - money hasn't moved yet)
     * 5. Returns refund details
     * 
     * Ledger write happens AFTER Stripe webhook confirms refund completion.
     */
    @Operation(
        summary = "Create a refund for a payment",
        description = "Creates a full refund for a captured payment. The refund is processed through Stripe and recorded in the ledger after Stripe confirms refund completion via webhook. Only CAPTURED payments can be refunded, and each payment can only be refunded once (full refund only)."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Refund created successfully",
                content = [Content(schema = Schema(implementation = RefundResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Refund creation failed (validation error, payment not CAPTURED, already refunded)",
                content = [Content(schema = Schema(implementation = RefundResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Payment not found",
                content = [Content(schema = Schema(implementation = RefundResponseDto::class))]
            )
        ]
    )
    @PostMapping("/payments/{paymentId}/refund")
    fun createRefund(
        @PathVariable paymentId: UUID
    ): ResponseEntity<RefundResponseDto> {
        return try {
            val refund = refundService.createRefund(paymentId)
            ResponseEntity.status(HttpStatus.CREATED).body(
                RefundResponseDto.fromDomain(refund)
            )
        } catch (e: RefundCreationException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                RefundResponseDto(
                    error = "Refund creation failed: ${e.message}"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                RefundResponseDto(
                    error = "Payment not found: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /payments/{paymentId}/refunds
     * Gets all refunds for a payment.
     */
    @Operation(
        summary = "Get refunds for a payment",
        description = "Retrieves all refunds for a specific payment. Currently, only one refund per payment is supported (full refund)."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Refunds retrieved successfully",
                content = [Content(schema = Schema(implementation = ListRefundsResponseDto::class))]
            )
        ]
    )
    @GetMapping("/payments/{paymentId}/refunds")
    fun getRefundsForPayment(
        @PathVariable paymentId: UUID
    ): ResponseEntity<ListRefundsResponseDto> {
        return try {
            val refunds = refundService.getRefundsByPaymentId(paymentId)
            ResponseEntity.ok(
                ListRefundsResponseDto(
                    refunds = refunds.map { RefundResponseDto.fromDomain(it) }
                )
            )
        } catch (e: Exception) {
            ResponseEntity.ok(
                ListRefundsResponseDto(
                    error = "Failed to retrieve refunds: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /refunds/{refundId}
     * Gets a refund by ID.
     */
    @Operation(
        summary = "Get refund by ID",
        description = "Retrieves a refund by its unique identifier."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Refund found",
                content = [Content(schema = Schema(implementation = RefundResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Refund not found",
                content = [Content(schema = Schema(implementation = RefundResponseDto::class))]
            )
        ]
    )
    @GetMapping("/refunds/{refundId}")
    fun getRefund(@PathVariable refundId: UUID): ResponseEntity<RefundResponseDto> {
        return try {
            val refund = refundService.getRefund(refundId)
            ResponseEntity.ok(RefundResponseDto.fromDomain(refund))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                RefundResponseDto(
                    error = "Refund not found: ${e.message}"
                )
            )
        }
    }
}

