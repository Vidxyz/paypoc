package com.payments.platform.payments.api

import com.payments.platform.payments.service.RefundCreationException
import com.payments.platform.payments.service.RefundService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/admin")
@Tag(name = "Admin - Refunds", description = "Admin-only refund operations for payments. All routes require admin authentication.")
class RefundController(
    private val refundService: RefundService
) {
    private val logger = LoggerFactory.getLogger(RefundController::class.java)
    
    /**
     * POST /admin/payments/{paymentId}/refund
     * Creates a full refund for a payment.
     * 
     * **DEPRECATED**: This endpoint is deprecated and should NOT be used.
     * 
     * **WARNING**: Using this endpoint will lead to orphaned orders. The order service will not be
     * notified of the refund, causing order refund status to remain incorrect. This breaks the
     * order-payment synchronization.
     * 
     * **Use instead**: 
     * - For full refunds: Use the order service endpoint POST /api/orders/{orderId}/refund (to be implemented)
     * - For partial refunds: Use POST /api/orders/{orderId}/partial-refund via the order service
     * 
     * Admin-only endpoint.
     * 
     * This endpoint orchestrates the refund workflow:
     * 1. Validates payment exists and is CAPTURED
     * 2. Validates payment hasn't been refunded already
     * 3. Creates Stripe Refund (full refund)
     * 4. Creates refund record (NO ledger write - money hasn't moved yet)
     * 5. Returns refund details
     * 
     * Ledger write happens AFTER Stripe webhook confirms refund completion.
     * 
     * **Problem**: This endpoint does not notify the order service, so order.refund_status
     * will not be updated, leading to data inconsistency.
     */
    @Operation(
        summary = "Create a refund for a payment (Admin) - DEPRECATED",
        description = "**DEPRECATED**: This endpoint is deprecated. Using it will lead to orphaned orders. Use order service refund endpoints instead. Creates a full refund for a captured payment. Admin-only endpoint. The refund is processed through Stripe and recorded in the ledger after Stripe confirms refund completion via webhook. Only CAPTURED payments can be refunded, and each payment can only be refunded once (full refund only). WARNING: This endpoint does not notify the order service, causing order refund status to remain incorrect."
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
    @Deprecated("Use order service refund endpoints instead. This endpoint causes orphaned orders.")
    fun createRefund(
        @PathVariable paymentId: UUID
    ): ResponseEntity<RefundResponseDto> {
        // Log warning about deprecated usage
        logger.warn("DEPRECATED: POST /admin/payments/{paymentId}/refund called. This will cause orphaned orders. Use order service refund endpoints instead.")
        
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
     * GET /admin/payments/{paymentId}/refunds
     * Gets all refunds for a payment.
     * 
     * Admin-only endpoint.
     */
    @Operation(
        summary = "Get refunds for a payment (Admin)",
        description = "Retrieves all refunds for a specific payment. Admin-only endpoint. Currently, only one refund per payment is supported (full refund)."
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
     * GET /admin/refunds/{refundId}
     * Gets a refund by ID.
     * 
     * Admin-only endpoint.
     */
    @Operation(
        summary = "Get refund by ID (Admin)",
        description = "Retrieves a refund by its unique identifier. Admin-only endpoint."
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

