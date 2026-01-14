package com.payments.platform.inventory.api

import com.payments.platform.inventory.auth.User
import com.payments.platform.inventory.config.AuthenticationInterceptor
import com.payments.platform.inventory.domain.Inventory
import com.payments.platform.inventory.service.InventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/inventory")
@Tag(name = "Inventory", description = "Inventory management APIs (SELLER or ADMIN only)")
class InventoryController(
    private val inventoryService: InventoryService
) {
    
    @GetMapping("/stock/{productId}")
    @Operation(
        summary = "Get stock by product ID",
        description = "Retrieves the current stock level for a given product ID. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Stock found"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Stock not found for product ID")
    ])
    fun getStockByProductId(
        @PathVariable productId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Inventory> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        val stock = inventoryService.getStockByProductId(productId)
        return if (stock != null) {
            ResponseEntity.ok(stock)
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    @GetMapping("/stock/seller/{sellerId}/sku/{sku}")
    @Operation(
        summary = "Get stock by seller ID and SKU",
        description = "Retrieves the current stock level for a given seller and SKU. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Stock found"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Stock not found")
    ])
    fun getStockBySellerAndSku(
        @PathVariable sellerId: String,
        @PathVariable sku: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Inventory> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        // SELLER can only view their own stock
        if (user.accountType == User.AccountType.SELLER && user.userId.toString() != sellerId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        
        val stock = inventoryService.getStockBySellerAndSku(sellerId, sku)
        return if (stock != null) {
            ResponseEntity.ok(stock)
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    @PutMapping("/stock/{productId}")
    @Operation(
        summary = "Create or update stock for a product",
        description = "Creates new stock for a product or updates existing stock. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Stock created or updated successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request data"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type")
    ])
    fun createOrUpdateStock(
        @PathVariable productId: UUID,
        @Valid @RequestBody request: CreateOrUpdateStockRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Inventory> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        // For now, sellerId is derived from the authenticated user
        // In the future, this could be passed in the request for ADMIN users
        val sellerId = user.userId.toString()
        
        val stock = inventoryService.createOrUpdateStock(
            productId = productId,
            sellerId = sellerId,
            sku = request.sku,
            quantity = request.quantity
        )
        return ResponseEntity.ok(stock)
    }
    
    @PostMapping("/stock/{inventoryId}/adjust")
    @Operation(
        summary = "Adjust stock quantity",
        description = "Adjusts the stock quantity by a delta (positive to add, negative to remove). Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Stock adjusted successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request data"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Inventory not found")
    ])
    fun adjustStock(
        @PathVariable inventoryId: UUID,
        @Valid @RequestBody request: AdjustStockRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Inventory> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        val stock = inventoryService.adjustStock(inventoryId, request.delta)
        return ResponseEntity.ok(stock)
    }
    
    @GetMapping("/low-stock")
    @Operation(
        summary = "Get low stock alerts",
        description = "Retrieves all inventory items that are at or below the low stock threshold. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of low stock items"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type")
    ])
    fun getLowStockItems(httpRequest: HttpServletRequest): ResponseEntity<List<Inventory>> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        val lowStockItems = inventoryService.getLowStockItems()
        return ResponseEntity.ok(lowStockItems)
    }
    
    private fun getCurrentUser(request: HttpServletRequest): User {
        val user = request.getAttribute(AuthenticationInterceptor.USER_ATTRIBUTE) as? User
            ?: throw IllegalStateException("User not found in request (authentication interceptor should have set this)")
        return user
    }
    
    private fun requireSellerOrAdmin(user: User) {
        if (user.accountType != User.AccountType.SELLER && user.accountType != User.AccountType.ADMIN) {
            throw IllegalStateException("Requires SELLER or ADMIN account type")
        }
    }
}

data class CreateOrUpdateStockRequest(
    val sku: String,
    @field:Min(0, message = "Quantity must be non-negative")
    val quantity: Int
)

data class AdjustStockRequest(
    val delta: Int  // Positive to add, negative to remove
)
