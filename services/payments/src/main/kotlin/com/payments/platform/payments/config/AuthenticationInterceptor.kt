package com.payments.platform.payments.config

import com.payments.platform.payments.auth.JwtValidator
import com.payments.platform.payments.models.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Authentication interceptor that validates Auth0 JWT tokens from the Authorization header
 * and sets the authenticated User in request attributes for use in controllers.
 */
@Component
class AuthenticationInterceptor(
    private val jwtValidator: JwtValidator
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    companion object {
        const val USER_ATTRIBUTE = "authenticatedUser"
    }
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Skip authentication for public endpoints
        if (isPublicEndpoint(request.requestURI)) {
            return true
        }
        
        // Extract bearer token from Authorization header
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // For endpoints that require authentication, return 401
            if (requiresAuthentication(request.requestURI, request.method)) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("""{"error":"Missing or invalid Authorization header"}""")
                return false
            }
            // For other endpoints, allow (they may have their own auth requirements)
            return true
        }
        
        val token = authHeader.substring(7) // Remove "Bearer " prefix
        
        // Validate JWT token and extract user
        val user = jwtValidator.validateAndExtractUser(token)
        if (user == null) {
            logger.warn("JWT token validation failed for request: ${request.requestURI}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Invalid or expired token"}""")
            return false
        }
        
        // Set user in request attributes for use in controllers
        request.setAttribute(USER_ATTRIBUTE, user)
        logger.debug("Authenticated request for user: ${user.userId} (${user.email})")
        
        return true
    }
    
    private fun isPublicEndpoint(uri: String): Boolean {
        return uri.startsWith("/actuator") ||
               uri.startsWith("/webhooks") ||
               uri.startsWith("/health") ||
               uri.startsWith("/healthz") ||
               uri.startsWith("/api/docs") ||
               uri.startsWith("/swagger-ui") ||
               uri.startsWith("/v3/api-docs")
    }
    
    private fun requiresAuthentication(uri: String, method: String): Boolean {
        // GET /payments requires authentication (list of user's payments)
        if ((uri == "/payments" || uri.startsWith("/payments?")) && method == "GET") {
            return true
        }
        // POST /payments requires authentication (create payment)
        if (uri == "/payments" && method == "POST") {
            return true
        }
        // All /payments/{id} endpoints require authentication
        if (uri.startsWith("/payments/") && uri.count { it == '/' } >= 2) {
            return true
        }
        return false
    }
}
