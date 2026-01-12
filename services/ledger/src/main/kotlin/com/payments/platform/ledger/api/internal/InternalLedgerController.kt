package com.payments.platform.ledger.api.internal

import com.payments.platform.ledger.auth.JwtValidator
import com.payments.platform.ledger.domain.Account
import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.service.LedgerService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Internal API for account management.
 * 
 * This API requires Bearer token (JWT) authentication.
 * Only ADMIN users can access these endpoints.
 * 
 * Endpoints:
 * - POST /internal/accounts - Create account (ADMIN only)
 * - DELETE /internal/accounts/{accountId} - Delete account (ADMIN only)
 */
@RestController
@RequestMapping("/internal")
class InternalLedgerController(
    private val ledgerService: LedgerService,
    private val jwtValidator: JwtValidator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Validates the bearer token and checks if user is ADMIN.
     * 
     * @return Pair<Boolean, String?> where first is isValid, second is error message if invalid
     */
    private fun validateBearerToken(request: HttpServletRequest): Pair<Boolean, String?> {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Pair(false, "Missing or invalid Authorization header. Bearer token required.")
        }
        
        val token = authHeader.substring(7) // Remove "Bearer " prefix
        
        // Validate token and extract account type
        val accountType = jwtValidator.validateAndExtractAccountType(token)
        if (accountType == null) {
            return Pair(false, "Invalid or expired bearer token")
        }
        
        // Check if user is ADMIN
        if (accountType != "ADMIN") {
            logger.warn("Non-ADMIN user (account_type: $accountType) attempted to access internal endpoint: ${request.requestURI}")
            return Pair(false, "Only ADMIN users can access this endpoint")
        }
        
        return Pair(true, null)
    }

    /**
     * POST /internal/accounts
     * Creates a new account in the ledger.
     * 
     * Requires: Authorization: Bearer {JWT token with ADMIN account_type}
     */
    @PostMapping("/accounts")
    fun createAccount(
        @RequestBody request: CreateAccountRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AccountResponse> {
        val (isValid, errorMessage) = validateBearerToken(httpRequest)
        if (!isValid) {
            val status = if (errorMessage?.contains("Only ADMIN") == true) {
                HttpStatus.FORBIDDEN
            } else {
                HttpStatus.UNAUTHORIZED
            }
            return ResponseEntity.status(status)
                .body(AccountResponse(error = errorMessage ?: "Unauthorized"))
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
     * Requires: Authorization: Bearer {JWT token with ADMIN account_type}
     */
    @DeleteMapping("/accounts/{accountId}")
    fun deleteAccount(
        @PathVariable accountId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AccountResponse> {
        val (isValid, errorMessage) = validateBearerToken(httpRequest)
        if (!isValid) {
            val status = if (errorMessage?.contains("Only ADMIN") == true) {
                HttpStatus.FORBIDDEN
            } else {
                HttpStatus.UNAUTHORIZED
            }
            return ResponseEntity.status(status)
                .body(AccountResponse(error = errorMessage ?: "Unauthorized"))
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
