package com.payments.platform.payments.api

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant

/**
 * Request to run reconciliation.
 */
@Schema(description = "Reconciliation request")
data class ReconciliationRequest(
    @JsonProperty("startDate")
    @field:NotNull(message = "startDate is required")
    @Schema(
        description = "Start date (inclusive) in ISO 8601 format",
        example = "2024-01-15T00:00:00Z",
        required = true
    )
    val startDate: Instant,
    
    @JsonProperty("endDate")
    @field:NotNull(message = "endDate is required")
    @Schema(
        description = "End date (inclusive) in ISO 8601 format",
        example = "2024-01-16T00:00:00Z",
        required = true
    )
    val endDate: Instant,
    
    @JsonProperty("currency")
    @Schema(
        description = "Optional currency filter (ISO 4217, uppercase, e.g., USD)",
        example = "USD",
        required = false
    )
    val currency: String? = null
)

