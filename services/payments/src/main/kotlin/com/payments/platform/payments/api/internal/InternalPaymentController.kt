package com.payments.platform.payments.api.internal

import com.payments.platform.payments.api.PaymentResponseDto
import com.payments.platform.payments.service.CreateOrderPaymentRequest
import com.payments.platform.payments.service.PaymentService
import com.payments.platform.payments.service.RefundService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Internal API for creating order-based payments.
 * 
 * This API is intended for use by other services (e.g., Order Service)
 * and requires authentication via an opaque token.
 * 
 * Endpoints:
 * - POST /internal/payments/order - Create payment for an order (one payment per order, multiple sellers)
 */
@RestController
@RequestMapping("/internal/payments")
@Tag(name = "Internal Payments", description = "Internal API for order-based payments (service-to-service only)")
class InternalPaymentController(
    private val paymentService: PaymentService,
    private val refundService: RefundService,
    @Value("\${payments.internal.api.token}") private val apiToken: String
) {
    
    /**
     * Validates the internal API token from the Authorization header.
     */
    private fun validateToken(request: HttpServletRequest): Boolean {
        val authHeader = request.getHeader("Authorization")
        return authHeader != null && authHeader == "Bearer $apiToken"
    }
    
    /**
     * POST /internal/payments/order
     * Creates a payment for an order with multiple sellers.
     * 
     * One payment per order - buyer pays once to BuyIt platform.
     * Ledger will split payment across multiple sellers.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/order")
    @Operation(
        summary = "Create order payment - Internal",
        description = "Creates a payment for an order. One payment per order, can have multiple sellers. Service-to-service only."
    )
    @ApiResponse(responseCode = "201", description = "Payment created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token")
    fun createOrderPayment(
        @Valid @RequestBody request: CreateOrderPaymentRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<PaymentResponseDto> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        return try {
            val response = paymentService.createOrderPayment(request)
            ResponseEntity.status(HttpStatus.CREATED).body(
                PaymentResponseDto.fromDomain(
                    payment = response.payment,
                    clientSecret = response.clientSecret
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                PaymentResponseDto(error = e.message)
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                PaymentResponseDto(error = "Failed to create payment: ${e.message}")
            )
        }
    }
    
    /**
     * POST /internal/payments/order/{orderId}/partial-refund
     * Creates a partial refund for a payment with multiple sellers.
     * 
     * Refunds are based on specific order items being refunded.
     * The refund amounts are calculated from the items being refunded.
     * 
     * The payment is looked up by orderId internally.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/order/{orderId}/partial-refund")
    @Operation(
        summary = "Create partial refund - Internal",
        description = "Creates a partial refund for a payment based on specific order items being refunded. Refund amounts are calculated from the items (price * quantity). Payment is looked up by orderId. Service-to-service only."
    )
    @ApiResponse(responseCode = "201", description = "Partial refund created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data or validation error")
    @ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token")
    @ApiResponse(responseCode = "404", description = "Payment not found for order")
    fun createPartialRefund(
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: CreatePartialRefundRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<com.payments.platform.payments.api.RefundResponseDto> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        return try {
            val refund = refundService.createPartialRefund(orderId, request)
            ResponseEntity.status(HttpStatus.CREATED).body(
                com.payments.platform.payments.api.RefundResponseDto.fromDomain(refund)
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                com.payments.platform.payments.api.RefundResponseDto(error = e.message)
            )
        } catch (e: com.payments.platform.payments.service.RefundCreationException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                com.payments.platform.payments.api.RefundResponseDto(error = "Refund creation failed: ${e.message}")
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                com.payments.platform.payments.api.RefundResponseDto(error = "Failed to create partial refund: ${e.message}")
            )
        }
    }
}

/**
 * Request to create a partial refund.
 * 
 * Refunds are based on specific order items being refunded.
 * The refund amounts are calculated from the items being refunded.
 */
data class CreatePartialRefundRequest(
    val orderItemsToRefund: List<OrderItemRefund>  // Order items to refund (with quantities)
)

/**
 * Order item to refund - specifies which items and how many to refund.
 */
data class OrderItemRefund(
    val orderItemId: UUID,  // Order item ID from order service
    val quantity: Int,  // Quantity to refund (must be <= original quantity)
    val sellerId: String,  // Seller ID for this item
    val priceCents: Long  // Price per item in cents (from original order)
)
