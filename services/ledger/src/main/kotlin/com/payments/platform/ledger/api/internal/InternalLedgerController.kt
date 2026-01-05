package com.payments.platform.ledger.api.internal

import com.payments.platform.ledger.domain.Account
import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.service.LedgerService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Internal API for account management.
 * 
 * This API is intended for use by other services (e.g., Payments Service)
 * and requires authentication via an opaque token.
 * 
 * Endpoints:
 * - POST /internal/accounts - Create account
 * - DELETE /internal/accounts/{accountId} - Delete account
 */
@RestController
@RequestMapping("/internal")
class InternalLedgerController(
    private val ledgerService: LedgerService,
    @Value("\${ledger.internal.api.token}") private val apiToken: String
) {

    /**
     * Validates the internal API token from the Authorization header.
     */
    private fun validateToken(request: HttpServletRequest): Boolean {
        val authHeader = request.getHeader("Authorization")
        return authHeader != null && authHeader == "Bearer $apiToken"
    }

    /**
     * POST /internal/accounts
     * Creates a new account in the ledger.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/accounts")
    fun createAccount(
        @RequestBody request: CreateAccountRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AccountResponse> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(AccountResponse(error = "Unauthorized: Invalid or missing token"))
        }

        return try {
            val accountType = try {
                AccountType.valueOf(request.accountType)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid account type: ${request.accountType}. " +
                    "Must be one of: ${AccountType.values().joinToString(", ")}"
                )
            }
            
            val account = ledgerService.createAccount(
                accountId = request.accountId ?: UUID.randomUUID(),
                accountType = accountType,
                referenceId = request.referenceId,
                currency = request.currency
            )
            
            ResponseEntity.status(HttpStatus.CREATED).body(
                AccountResponse(
                    accountId = account.id,
                    accountType = account.accountType.name,
                    referenceId = account.referenceId,
                    currency = account.currency,
                    createdAt = account.createdAt.toString()
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                AccountResponse(error = "Failed to create account: ${e.message}")
            )
        }
    }

    /**
     * DELETE /internal/accounts/{accountId}
     * Deletes an account.
     * 
     * Note: This will fail if there are associated ledger entries (foreign key constraint).
     * 
     * Requires: Authorization: Bearer {token}
     */
    @DeleteMapping("/accounts/{accountId}")
    fun deleteAccount(
        @PathVariable accountId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AccountResponse> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(AccountResponse(error = "Unauthorized: Invalid or missing token"))
        }

        return try {
            // Note: We don't have a deleteAccount method in LedgerService yet
            // For now, return an error. This can be implemented later if needed.
            ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
                AccountResponse(error = "Account deletion not yet implemented")
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                AccountResponse(error = "Account not found: ${e.message}")
            )
        }
    }
}

data class CreateAccountRequest(
    val accountId: UUID? = null,  // If null, will be generated
    val accountType: String,  // STRIPE_CLEARING, SELLER_PAYABLE, BUYIT_REVENUE, etc.
    val referenceId: String? = null,  // e.g., seller_id for SELLER_PAYABLE
    val currency: String
)

data class AccountResponse(
    val accountId: UUID? = null,
    val accountType: String? = null,
    val referenceId: String? = null,
    val currency: String? = null,
    val createdAt: String? = null,
    val message: String? = null,
    val error: String? = null
)
