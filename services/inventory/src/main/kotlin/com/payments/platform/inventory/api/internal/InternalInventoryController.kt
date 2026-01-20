package com.payments.platform.inventory.api.internal

import com.payments.platform.inventory.domain.Inventory
import com.payments.platform.inventory.domain.Reservation
import com.payments.platform.inventory.service.InventoryService
import com.payments.platform.inventory.service.ReservationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Internal API for inventory operations.
 * 
 * This API is intended for use by other services (e.g., Catalog Service, Cart Service)
 * and requires authentication via an opaque token.
 * 
     * Endpoints:
     * - POST /internal/stock/batch - Get stock information for multiple products
     * - GET /internal/stock/{productId} - Get stock by product ID
     * - POST /internal/reservations - Create soft reservation
     * - POST /internal/reservations/{reservationId}/allocate - Allocate reservation
     * - POST /internal/reservations/{reservationId}/release - Release reservation
     * - POST /internal/reservations/{reservationId}/confirm-sale - Confirm sale
 */
@RestController
@RequestMapping("/internal")
@Tag(name = "Internal Inventory", description = "Internal inventory APIs for service-to-service communication")
class InternalInventoryController(
    private val inventoryService: InventoryService,
    private val reservationService: ReservationService,
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
    @PostMapping("/stock/batch")
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
    
    /**
     * GET /internal/stock/{productId}
     * Retrieves stock information for a single product.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @GetMapping("/stock/{productId}")
    @Operation(
        summary = "Get stock by product ID - Internal",
        description = "Retrieves stock information for a single product. Service-to-service only."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Inventory found"),
        ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token"),
        ApiResponse(responseCode = "404", description = "Stock not found for product ID")
    ])
    fun getStockByProductId(
        @PathVariable productId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Inventory> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        val stock = inventoryService.getStockByProductId(productId)
        return if (stock != null) {
            ResponseEntity.ok(stock)
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    /**
     * POST /internal/reservations
     * Creates a soft reservation (add-to-cart).
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/reservations")
    @Operation(
        summary = "Create soft reservation - Internal",
        description = "Creates a soft reservation for stock (15min TTL). Moves stock from available to reserved. Service-to-service only."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Reservation created successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request data or not enough stock"),
        ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token"),
        ApiResponse(responseCode = "404", description = "Inventory not found")
    ])
    fun createSoftReservation(
        @Valid @RequestBody request: InternalCreateReservationRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        return try {
            val reservation = reservationService.createSoftReservation(
                inventoryId = request.inventoryId,
                cartId = request.cartId,
                quantity = request.quantity
            )
            ResponseEntity.status(HttpStatus.CREATED).body(reservation)
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
    
    /**
     * POST /internal/reservations/{reservationId}/allocate
     * Allocates a reservation (converts soft reservation to hard allocation).
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/reservations/{reservationId}/allocate")
    @Operation(
        summary = "Allocate reservation - Internal",
        description = "Converts a soft reservation to a hard allocation (checkout). Moves stock from reserved to allocated. Service-to-service only."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Reservation allocated successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request data or reservation cannot be allocated"),
        ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token"),
        ApiResponse(responseCode = "404", description = "Reservation not found")
    ])
    fun allocateReservation(
        @PathVariable reservationId: UUID,
        @RequestBody(required = false) request: AllocateReservationRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        return try {
            val orderId = request?.orderId ?: reservationId // Use request orderId if provided, otherwise fall back to reservationId
            val reservation = reservationService.allocateReservation(reservationId, orderId)
            ResponseEntity.ok(reservation)
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
    
    /**
     * POST /internal/reservations/{reservationId}/release
     * Releases a reservation.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/reservations/{reservationId}/release")
    @Operation(
        summary = "Release reservation - Internal",
        description = "Releases a reservation and returns stock to available. Service-to-service only."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Reservation released successfully"),
        ApiResponse(responseCode = "400", description = "Cannot release a sold reservation"),
        ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token"),
        ApiResponse(responseCode = "404", description = "Reservation not found")
    ])
    fun releaseReservation(
        @PathVariable reservationId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        return try {
            val reservation = reservationService.releaseReservation(reservationId)
            ResponseEntity.ok(reservation)
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
    
    /**
     * POST /internal/reservations/{reservationId}/confirm-sale
     * Confirms a sale after payment completion.
     * 
     * Requires: Authorization: Bearer {token}
     */
    @PostMapping("/reservations/{reservationId}/confirm-sale")
    @Operation(
        summary = "Confirm sale - Internal",
        description = "Confirms a sale after payment completion. Moves stock from allocated to sold (deducts from total). Service-to-service only."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Sale confirmed successfully"),
        ApiResponse(responseCode = "400", description = "Reservation not in allocated state"),
        ApiResponse(responseCode = "401", description = "Unauthorized: Invalid or missing token"),
        ApiResponse(responseCode = "404", description = "Reservation not found")
    ])
    fun confirmSale(
        @PathVariable reservationId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        if (!validateToken(httpRequest)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        
        return try {
            val reservation = reservationService.confirmSale(reservationId)
            ResponseEntity.ok(reservation)
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
}

data class AllocateReservationRequest(
    val orderId: UUID
)

data class BatchStockRequest(
    @field:Valid
    val productIds: List<@jakarta.validation.constraints.NotNull UUID>
)

data class InternalCreateReservationRequest(
    val inventoryId: UUID,
    val cartId: UUID,
    @field:Min(1, message = "Quantity must be at least 1")
    val quantity: Int
)
