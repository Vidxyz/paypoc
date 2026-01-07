package com.payments.platform.payments.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Simple authentication interceptor that extracts bearer token from Authorization header
 * and sets the buyerId in request attributes for use in controllers.
 * 
 * For now, uses a static mapping: token "buyer123_token" -> buyerId "buyer123"
 * In production, this would validate JWT tokens or query a user service.
 */
@Component
class AuthenticationInterceptor : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    // Static token mapping for now
    // In production, this would be replaced with JWT validation or user service lookup
    private val tokenToBuyerId = mapOf(
        "buyer123_token" to "buyer123"
    )
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Skip authentication for public endpoints (e.g., health checks, webhooks)
        if (request.requestURI.startsWith("/actuator") || 
            request.requestURI.startsWith("/webhooks")) {
            return true
        }
        
        // Extract bearer token from Authorization header
        val authHeader = request.getHeader("Authorization")
        // todo-vh: Split exclusions into a separate list
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // For GET /payments, require authentication (handle with or without query params)
            if (request.requestURI.startsWith("/payments") && request.method == "GET" && !request.requestURI.startsWith("/payments/")) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("""{"error":"Missing or invalid Authorization header"}""")
                return false
            }
            // For other endpoints, allow (they may have their own auth requirements)
            return true
        }
        
        val token = authHeader.substring(7) // Remove "Bearer " prefix
        
        // Look up buyerId from token
        val buyerId = tokenToBuyerId[token]
        if (buyerId == null) {
            logger.warn("Invalid bearer token: ${token.take(10)}...")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Invalid bearer token"}""")
            return false
        }
        
        // Set buyerId in request attributes for use in controllers
        request.setAttribute("buyerId", buyerId)
        logger.debug("Authenticated request for buyerId: $buyerId")
        
        return true
    }
}

