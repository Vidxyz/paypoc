package com.payments.platform.payments.client.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Response DTO for transaction query from ledger.
 * 
 * This matches the Ledger Service API contract.
 */
data class LedgerTransactionQueryResponse(
    @JsonProperty("transactions")
    val transactions: List<LedgerTransactionWithEntries>,
    
    @JsonProperty("error")
    val error: String? = null
)

data class LedgerTransactionWithEntries(
    @JsonProperty("transaction")
    val transaction: LedgerTransactionDto,
    
    @JsonProperty("entries")
    val entries: List<LedgerEntryDto>
)

data class LedgerTransactionDto(
    @JsonProperty("transactionId")
    val transactionId: UUID,
    
    @JsonProperty("referenceId")
    val referenceId: String,
    
    @JsonProperty("idempotencyKey")
    val idempotencyKey: String,
    
    @JsonProperty("description")
    val description: String,
    
    @JsonProperty("createdAt")
    val createdAt: String
)

data class LedgerEntryDto(
    @JsonProperty("entryId")
    val entryId: UUID? = null,
    
    @JsonProperty("accountId")
    val accountId: UUID,
    
    @JsonProperty("direction")
    val direction: String,
    
    @JsonProperty("amountCents")
    val amountCents: Long,
    
    @JsonProperty("currency")
    val currency: String,
    
    @JsonProperty("createdAt")
    val createdAt: String? = null
)

