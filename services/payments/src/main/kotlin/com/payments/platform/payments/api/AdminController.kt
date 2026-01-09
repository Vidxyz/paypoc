package com.payments.platform.payments.api

import com.payments.platform.payments.client.LedgerClient
import com.payments.platform.payments.service.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Admin-only controller for administrative operations.
 * 
 * This controller provides endpoints for admin users to:
 * - View all payments (for refunding any transaction)
 * - View all sellers with their balances (for initiating payouts)
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Admin-only operations")
class AdminController(
    private val paymentService: PaymentService,
    private val ledgerClient: LedgerClient
) {
    
    /**
     * GET /admin/payments
     * Gets all payments (admin-only).
     * 
     * Supports pagination and sorting:
     * - page: Page number (0-indexed, default: 0)
     * - size: Page size (default: 50)
     * - sortBy: Field to sort by (default: "createdAt")
     * - sortDirection: Sort direction - ASC or DESC (default: DESC)
     */
    @Operation(
        summary = "Get all payments (admin)",
        description = "Retrieves all payments in the system. Admin-only endpoint. Supports pagination and sorting."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Payments retrieved successfully",
                content = [Content(schema = Schema(implementation = ListPaymentsResponseDto::class))]
            )
        ]
    )
    @GetMapping("/payments")
    fun getAllPayments(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "createdAt") sortBy: String,
        @RequestParam(defaultValue = "DESC") sortDirection: String
    ): ResponseEntity<ListPaymentsResponseDto> {
        return try {
            val sort = Sort.by(
                if (sortDirection.uppercase() == "ASC") Sort.Direction.ASC else Sort.Direction.DESC,
                sortBy
            )
            val pageable = PageRequest.of(page, size, sort)
            
            val payments = paymentService.getAllPayments(pageable)
            val paymentIds = payments.content.map { it.id }
            
            // Get chargeback info for all payments in a single optimized query
            val chargebackInfoMap = paymentService.getChargebackInfoForPayments(paymentIds)
            
            ResponseEntity.ok(
                ListPaymentsResponseDto(
                    payments = payments.content.map { payment ->
                        PaymentResponseDto.fromDomain(payment, chargebackInfo = chargebackInfoMap[payment.id])
                    },
                    page = payments.number,
                    size = payments.size,
                    total = payments.totalElements.toInt()
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ListPaymentsResponseDto(
                    error = "Failed to retrieve payments: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /admin/sellers
     * Gets all sellers with their balances (admin-only).
     * 
     * Returns a list of sellers with their SELLER_PAYABLE account balances.
     */
    @Operation(
        summary = "Get all sellers with balances (admin)",
        description = "Retrieves all sellers with their SELLER_PAYABLE account balances. Admin-only endpoint. " +
            "Useful for viewing which sellers have pending funds available for payout."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Sellers retrieved successfully",
                content = [Content(schema = Schema(implementation = ListSellersResponseDto::class))]
            )
        ]
    )
    @GetMapping("/sellers")
    fun getAllSellers(): ResponseEntity<ListSellersResponseDto> {
        return try {
            val sellers = ledgerClient.getAllSellerAccounts()
            if (sellers.error != null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ListSellersResponseDto(
                        error = "Failed to retrieve sellers: ${sellers.error}"
                    )
                )
            }
            
            ResponseEntity.ok(
                ListSellersResponseDto(
                    sellers = sellers.sellers.map { seller ->
                        SellerWithBalanceDto(
                            sellerId = seller.sellerId,
                            currency = seller.currency,
                            accountId = seller.accountId,
                            balanceCents = seller.balanceCents
                        )
                    }
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ListSellersResponseDto(
                    error = "Failed to retrieve sellers: ${e.message}"
                )
            )
        }
    }
}

