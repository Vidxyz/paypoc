package com.payments.platform.inventory.api.internal

import com.payments.platform.inventory.domain.Inventory
import com.payments.platform.inventory.service.InventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Internal API for inventory operations.
 * 
 * This API is intended for use by other services (e.g., Catalog Service)
 * and requires authentication via an opaque token.
 * 
 * Endpoints:
 * - POST /internal/stock/batch - Get stock information for multiple products
 */
@RestController
@RequestMapping("/internal/stock")
@Tag(name = "Internal Inventory", description = "Internal inventory APIs for service-to-service communication")
class InternalInventoryController(
    private val inventoryService: InventoryService,
    @Value("\${inventory.internal.api.token}") private val apiToken: String
) {
    
    /**
     * Validates the internal API token from the Authorization header.
     */
    private fun validateToken(request: HttpServletRequest): Boolean {
        val authHeader = request.getHeader("Authorization")
        return authHeader != null && authHeader == "Bearer $apiToken"
    }
    
    /**
     * POST /internal/stock/batch
     * Retrieves stock information for multiple products in a single request.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/batch")
    @Operation(
        summary = "Get stock by multiple product IDs (batch) - Internal",
        description = "Retrieves stock information for multiple products in a single request. Returns a map of productId to Inventory. Service-to-service only."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Map of product IDs to inventory (only includes products that have inventory)"),
        ApiResponse(responseCode = "400", description = "Invalid request data"),
        ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token")
    ])
    fun getStockBatch(
        @Valid @RequestBody request: BatchStockRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Map<String, Inventory>> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        val stockMap = inventoryService.getStockByProductIds(request.productIds)
        // Convert UUID keys to strings for JSON serialization
        val responseMap = stockMap.mapKeys { it.key.toString() }
        return ResponseEntity.ok(responseMap)
    }
}

data class BatchStockRequest(
    @field:Valid
    val productIds: List<@jakarta.validation.constraints.NotNull UUID>
)
