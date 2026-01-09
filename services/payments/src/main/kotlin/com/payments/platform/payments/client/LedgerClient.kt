package com.payments.platform.payments.client

import com.payments.platform.payments.client.dto.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

/**
 * HTTP client for communicating with the Ledger Service.
 * 
 * This is a synchronous client (using block()) because we're in a synchronous
 * orchestration phase. Later, this could be made async when we add Kafka.
 */
@Component
class LedgerClient(
    private val webClient: WebClient,
    @Value("\${ledger.service.url}") private val ledgerServiceUrl: String
) {
    
    /**
     * Creates a transaction in the ledger.
     * 
     * This is the critical call - if this fails, the payment should not be created.
     * 
     * @param request Transaction request with account, amount, currency, idempotency key
     * @return Ledger transaction response
     * @throws LedgerClientException if ledger rejects the transaction
     */
    fun postTransaction(request: LedgerTransactionRequest): LedgerTransactionResponse {
        return try {
            webClient.post()
                .uri("$ledgerServiceUrl/ledger/transactions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LedgerTransactionResponse::class.java)
                .block() ?: throw LedgerClientException("Ledger service returned null response")
        } catch (e: WebClientResponseException) {
            when (e.statusCode) {
                HttpStatus.BAD_REQUEST -> throw LedgerClientException(
                    "Ledger rejected transaction: ${e.responseBodyAsString}",
                    e
                )
                HttpStatus.UNPROCESSABLE_ENTITY -> throw LedgerClientException(
                    "Insufficient funds in ledger: ${e.responseBodyAsString}",
                    e
                )
                else -> throw LedgerClientException(
                    "Ledger service error: ${e.statusCode} - ${e.responseBodyAsString}",
                    e
                )
            }
        } catch (e: Exception) {
            throw LedgerClientException("Failed to communicate with ledger service", e)
        }
    }
    
    /**
     * Gets the balance for an account from the ledger.
     * 
     * This is a read-only delegation - Payments never computes balances.
     * 
     * @param accountId Account ID to query
     * @return Balance response from ledger
     * @throws LedgerClientException if ledger service is unavailable
     */
    fun getBalance(accountId: UUID): LedgerBalanceResponse {
        return try {
            webClient.get()
                .uri("$ledgerServiceUrl/ledger/accounts/$accountId/balance")
                .retrieve()
                .bodyToMono(LedgerBalanceResponse::class.java)
                .block() ?: throw LedgerClientException("Ledger service returned null response")
        } catch (e: WebClientResponseException) {
            when (e.statusCode) {
                HttpStatus.NOT_FOUND -> throw LedgerClientException(
                    "Account not found in ledger: $accountId",
                    e
                )
                else -> throw LedgerClientException(
                    "Ledger service error: ${e.statusCode} - ${e.responseBodyAsString}",
                    e
                )
            }
        } catch (e: Exception) {
            throw LedgerClientException("Failed to communicate with ledger service", e)
        }
    }
    
    /**
     * Queries transactions from the ledger by date range and optional currency filter.
     * 
     * Used for reconciliation to compare ledger transactions with Stripe balance transactions.
     * 
     * @param startDate Start date (inclusive) for filtering transactions
     * @param endDate End date (inclusive) for filtering transactions
     * @param currency Optional currency filter (ISO 4217, uppercase, e.g., "USD")
     * @return Transaction query response from ledger
     * @throws LedgerClientException if ledger service is unavailable or request is invalid
     */
    fun queryTransactions(
        startDate: Instant,
        endDate: Instant,
        currency: String? = null
    ): LedgerTransactionQueryResponse {
        return try {
            val uriBuilder = UriComponentsBuilder
                .fromUriString("$ledgerServiceUrl/ledger/transactions")
                .queryParam("startDate", startDate.toString())
                .queryParam("endDate", endDate.toString())
            
            if (currency != null) {
                uriBuilder.queryParam("currency", currency)
            }
            
            webClient.get()
                .uri(uriBuilder.build().toUri())
                .retrieve()
                .bodyToMono(LedgerTransactionQueryResponse::class.java)
                .block() ?: throw LedgerClientException("Ledger service returned null response")
        } catch (e: WebClientResponseException) {
            when (e.statusCode) {
                HttpStatus.BAD_REQUEST -> throw LedgerClientException(
                    "Invalid reconciliation request: ${e.responseBodyAsString}",
                    e
                )
                else -> throw LedgerClientException(
                    "Ledger service error: ${e.statusCode} - ${e.responseBodyAsString}",
                    e
                )
            }
        } catch (e: Exception) {
            throw LedgerClientException("Failed to communicate with ledger service", e)
        }
    }
    
    /**
     * Gets all seller accounts with their balances (admin-only).
     * 
     * Returns a list of all SELLER_PAYABLE accounts with their current balances.
     * 
     * @return List of seller accounts with balances
     * @throws LedgerClientException if ledger service is unavailable
     */
    fun getAllSellerAccounts(): LedgerSellersResponse {
        return try {
            webClient.get()
                .uri("$ledgerServiceUrl/ledger/admin/sellers")
                .retrieve()
                .bodyToMono(LedgerSellersResponse::class.java)
                .block() ?: throw LedgerClientException("Ledger service returned null response")
        } catch (e: WebClientResponseException) {
            throw LedgerClientException(
                "Ledger service error: ${e.statusCode} - ${e.responseBodyAsString}",
                e
            )
        } catch (e: Exception) {
            throw LedgerClientException("Failed to communicate with ledger service", e)
        }
    }
}

/**
 * Exception thrown when ledger client operations fail.
 */
class LedgerClientException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

