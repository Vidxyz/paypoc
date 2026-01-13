package com.payments.platform.payments.api

import com.payments.platform.payments.domain.PayoutState
import com.payments.platform.payments.service.PayoutCreationException
import com.payments.platform.payments.service.PayoutService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/admin")
@Tag(name = "Admin - Payouts", description = "Admin-only payout operations for sellers. All routes require admin authentication.")
class PayoutController(
    private val payoutService: PayoutService
) {
    
    /**
     * POST /admin/payouts
     * Creates a manual payout for a seller.
     * 
     * Admin-only endpoint.
     * 
     * This endpoint orchestrates the payout workflow:
     * 1. Validates seller has Stripe account configured
     * 2. Creates Stripe Transfer
     * 3. Creates payout record (NO ledger write - money hasn't moved yet)
     * 4. Returns payout details
     * 
     * Ledger write happens AFTER Stripe webhook confirms transfer completion.
     */
    @Operation(
        summary = "Create a payout for a seller (Admin)",
        description = "Creates a manual payout for a seller. Admin-only endpoint. The payout is processed through Stripe and recorded in the ledger after Stripe confirms transfer completion via webhook. The seller's Stripe account must be configured before payouts can be processed."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Payout created successfully",
                content = [Content(schema = Schema(implementation = PayoutResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Payout creation failed (validation error, seller not configured)",
                content = [Content(schema = Schema(implementation = PayoutResponseDto::class))]
            )
        ]
    )
    @PostMapping("/payouts")
    fun createPayout(
        @Valid @RequestBody request: CreatePayoutRequestDto
    ): ResponseEntity<PayoutResponseDto> {
        return try {
            val payout = payoutService.createPayout(
                sellerId = request.sellerId,
                amountCents = request.amountCents,
                currency = request.currency,
                description = request.description
            )
            ResponseEntity.status(HttpStatus.CREATED).body(
                PayoutResponseDto.fromDomain(payout)
            )
        } catch (e: PayoutCreationException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                PayoutResponseDto(
                    error = "Payout creation failed: ${e.message}"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                PayoutResponseDto(
                    error = "Invalid request: ${e.message}"
                )
            )
        }
    }
    
    /**
     * POST /admin/sellers/{sellerId}/payout
     * Creates a payout for all pending funds for a seller.
     * 
     * Admin-only endpoint.
     * 
     * This is a convenience endpoint that automatically calculates and pays out
     * all pending funds for a seller based on their SELLER_PAYABLE balance in the ledger.
     */
    @Operation(
        summary = "Payout all pending funds for a seller (Admin)",
        description = "Creates a payout for all pending funds for a seller. Admin-only endpoint. This endpoint queries the ledger for the seller's SELLER_PAYABLE balance and creates a payout for that amount. If the seller has no pending funds, returns an error."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Payout created successfully",
                content = [Content(schema = Schema(implementation = PayoutResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Payout creation failed (no pending funds, seller not configured)",
                content = [Content(schema = Schema(implementation = PayoutResponseDto::class))]
            )
        ]
    )
    @PostMapping("/sellers/{sellerId}/payout")
    fun createPayoutForPendingFunds(
        @PathVariable sellerId: String,
        @RequestParam(defaultValue = "CAD") currency: String
    ): ResponseEntity<PayoutResponseDto> {
        return try {
            val payout = payoutService.createPayoutForPendingFunds(
                sellerId = sellerId,
                currency = currency
            )
            ResponseEntity.status(HttpStatus.CREATED).body(
                PayoutResponseDto.fromDomain(payout)
            )
        } catch (e: PayoutCreationException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                PayoutResponseDto(
                    error = "Payout creation failed: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /admin/payouts
     * Gets payouts with optional filters.
     * 
     * Admin-only endpoint.
     */
    @Operation(
        summary = "Get payouts (Admin)",
        description = "Retrieves payouts. Admin-only endpoint. Can be filtered by sellerId and state."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Payouts retrieved successfully",
                content = [Content(schema = Schema(implementation = ListPayoutsResponseDto::class))]
            )
        ]
    )
    @GetMapping("/payouts")
    fun getPayouts(
        @RequestParam(required = false) sellerId: String?,
        @RequestParam(required = false) state: String?
    ): ResponseEntity<ListPayoutsResponseDto> {
        return try {
            val payouts = when {
                sellerId != null -> payoutService.getPayoutsBySellerId(sellerId)
                else -> payoutService.getPayoutsBySellerId("") // Empty list if no sellerId
            }
            
            val filteredPayouts = if (state != null) {
                try {
                    val payoutState = PayoutState.valueOf(state.uppercase())
                    payouts.filter { it.state == payoutState }
                } catch (e: IllegalArgumentException) {
                    emptyList()
                }
            } else {
                payouts
            }
            
            ResponseEntity.ok(
                ListPayoutsResponseDto(
                    payouts = filteredPayouts.map { PayoutResponseDto.fromDomain(it) }
                )
            )
        } catch (e: Exception) {
            ResponseEntity.ok(
                ListPayoutsResponseDto(
                    error = "Failed to retrieve payouts: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /admin/payouts/{payoutId}
     * Gets a payout by ID.
     * 
     * Admin-only endpoint.
     */
    @Operation(
        summary = "Get payout by ID (Admin)",
        description = "Retrieves a payout by its unique identifier. Admin-only endpoint."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Payout found",
                content = [Content(schema = Schema(implementation = PayoutResponseDto::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Payout not found",
                content = [Content(schema = Schema(implementation = PayoutResponseDto::class))]
            )
        ]
    )
    @GetMapping("/payouts/{payoutId}")
    fun getPayout(@PathVariable payoutId: UUID): ResponseEntity<PayoutResponseDto> {
        return try {
            val payout = payoutService.getPayout(payoutId)
            ResponseEntity.ok(PayoutResponseDto.fromDomain(payout))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                PayoutResponseDto(
                    error = "Payout not found: ${e.message}"
                )
            )
        }
    }
    
    /**
     * GET /admin/sellers/{sellerId}/payouts
     * Gets all payouts for a seller.
     * 
     * Admin-only endpoint.
     */
    @Operation(
        summary = "Get payouts for a seller (Admin)",
        description = "Retrieves all payouts for a specific seller. Admin-only endpoint."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Payouts retrieved successfully",
                content = [Content(schema = Schema(implementation = ListPayoutsResponseDto::class))]
            )
        ]
    )
    @GetMapping("/sellers/{sellerId}/payouts")
    fun getPayoutsForSeller(
        @PathVariable sellerId: String
    ): ResponseEntity<ListPayoutsResponseDto> {
        return try {
            val payouts = payoutService.getPayoutsBySellerId(sellerId)
            ResponseEntity.ok(
                ListPayoutsResponseDto(
                    payouts = payouts.map { PayoutResponseDto.fromDomain(it) }
                )
            )
        } catch (e: Exception) {
            ResponseEntity.ok(
                ListPayoutsResponseDto(
                    error = "Failed to retrieve payouts: ${e.message}"
                )
            )
        }
    }
}

