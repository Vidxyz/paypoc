package com.payments.platform.inventory.api

import com.payments.platform.inventory.auth.User
import com.payments.platform.inventory.config.AuthenticationInterceptor
import com.payments.platform.inventory.domain.Reservation
import com.payments.platform.inventory.service.ReservationService
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
@Tag(name = "Reservations", description = "Stock reservation management APIs (SELLER or ADMIN only)")
class ReservationController(
    private val reservationService: ReservationService
) {
    
    @PostMapping("/reserve")
    @Operation(
        summary = "Create a soft reservation (add-to-cart)",
        description = "Creates a soft reservation for stock (15min TTL). Moves stock from available to reserved. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Reservation created successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request data or not enough stock"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Inventory not found")
    ])
    fun createSoftReservation(
        @Valid @RequestBody request: CreateSoftReservationRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
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
    
    @PostMapping("/allocate")
    @Operation(
        summary = "Create a hard allocation (checkout)",
        description = "Creates a hard allocation for stock (30min TTL, until payment completes/fails). Moves stock from reserved to allocated. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Allocation created successfully"),
        ApiResponse(responseCode = "400", description = "Invalid request data or not enough reserved stock"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Inventory not found")
    ])
    fun createHardAllocation(
        @Valid @RequestBody request: CreateHardAllocationRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        return try {
            val reservation = reservationService.createHardAllocation(
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
    
    @PostMapping("/release")
    @Operation(
        summary = "Release a reservation",
        description = "Releases a reservation and returns stock to available. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Reservation released successfully"),
        ApiResponse(responseCode = "400", description = "Cannot release a sold reservation"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Reservation not found")
    ])
    fun releaseReservation(
        @RequestBody request: ReleaseReservationRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        return try {
            val reservation = reservationService.releaseReservation(request.reservationId)
            ResponseEntity.ok(reservation)
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
    
    @PostMapping("/confirm-sale")
    @Operation(
        summary = "Confirm sale (after payment)",
        description = "Confirms a sale after payment completion. Moves stock from allocated to sold (deducts from total). Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Sale confirmed successfully"),
        ApiResponse(responseCode = "400", description = "Reservation not in allocated state"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Reservation not found")
    ])
    fun confirmSale(
        @RequestBody request: ConfirmSaleRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        return try {
            val reservation = reservationService.confirmSale(request.reservationId)
            ResponseEntity.ok(reservation)
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }
    
    @GetMapping("/reservations/{reservationId}")
    @Operation(
        summary = "Get reservation by ID",
        description = "Retrieves details of a specific reservation. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Reservation found"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type"),
        ApiResponse(responseCode = "404", description = "Reservation not found")
    ])
    fun getReservationById(
        @PathVariable reservationId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Reservation> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        val reservation = reservationService.getReservationById(reservationId)
        return if (reservation != null) {
            ResponseEntity.ok(reservation)
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    @GetMapping("/reservations/by-cart/{cartId}")
    @Operation(
        summary = "Get reservations by cart ID",
        description = "Retrieves all reservations associated with a specific cart. Requires SELLER or ADMIN account type."
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of reservations"),
        ApiResponse(responseCode = "401", description = "Authentication required"),
        ApiResponse(responseCode = "403", description = "Requires SELLER or ADMIN account type")
    ])
    fun getReservationsByCartId(
        @PathVariable cartId: UUID,
        httpRequest: HttpServletRequest
    ): ResponseEntity<List<Reservation>> {
        val user = getCurrentUser(httpRequest)
        requireSellerOrAdmin(user)
        
        val reservations = reservationService.getReservationsByCartId(cartId)
        return ResponseEntity.ok(reservations)
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

data class CreateSoftReservationRequest(
    val inventoryId: UUID,
    val cartId: UUID,
    @field:Min(1, message = "Quantity must be at least 1")
    val quantity: Int
)

data class CreateHardAllocationRequest(
    val inventoryId: UUID,
    val cartId: UUID,
    @field:Min(1, message = "Quantity must be at least 1")
    val quantity: Int
)

data class ReleaseReservationRequest(
    val reservationId: UUID
)

data class ConfirmSaleRequest(
    val reservationId: UUID
)
