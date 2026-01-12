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
        // Allow OPTIONS requests (CORS preflight) to pass through
        if (request.method == "OPTIONS") {
            return true
        }
        
        // Skip authentication for public endpoints
        if (isPublicEndpoint(request.requestURI)) {
            return true
        }
        
        // All other endpoints require authentication
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Missing or invalid Authorization header. Bearer token required."}""")
            return false
        }
        
        val token = authHeader.substring(7) // Remove "Bearer " prefix
        
        // Validate JWT token and extract user
        val user = jwtValidator.validateAndExtractUser(token)
        if (user == null) {
            logger.warn("JWT token validation failed for request: ${request.requestURI}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Invalid or expired bearer token"}""")
            return false
        }
        
        // Check if this is an admin-only route
        if (isAdminRoute(request.requestURI)) {
            if (user.accountType != User.AccountType.ADMIN) {
                logger.warn("Non-ADMIN user (${user.userId}, account_type: ${user.accountType}) attempted to access admin route: ${request.requestURI}")
                response.status = HttpServletResponse.SC_FORBIDDEN
                response.contentType = "application/json"
                response.writer.write("""{"error":"Only ADMIN users can access this endpoint"}""")
                return false
            }
        }
        
        // Set user in request attributes for use in controllers
        request.setAttribute(USER_ATTRIBUTE, user)
        logger.debug("Authenticated request for user: ${user.userId} (${user.email}, account_type: ${user.accountType})")
        
        return true
    }
    
    private fun isPublicEndpoint(uri: String): Boolean {
        return uri.startsWith("/actuator") ||
               uri.startsWith("/webhooks") ||
               uri.startsWith("/health") ||
               uri.startsWith("/healthz") ||
               uri.startsWith("/api/docs") ||
               uri.startsWith("/api/swagger-ui") ||
               uri.startsWith("/swagger-ui") ||
               uri.startsWith("/swagger-resources") ||
               uri.startsWith("/v3/api-docs") ||
               uri.startsWith("/api/v3/api-docs") ||
               uri.startsWith("/configuration") ||
               uri.startsWith("/webjars")
    }
    
    /**
     * Checks if a route is admin-only (contains "admin" in the path).
     * Examples:
     * - /admin/payments -> true
     * - /admin/payouts -> true
     * - /admin/refunds -> true
     * - /payments -> false
     * - /reconciliation -> false
     */
    private fun isAdminRoute(uri: String): Boolean {
        // Check if URI contains "/admin" as a path segment
        val pathSegments = uri.split("/").filter { it.isNotBlank() }
        return pathSegments.contains("admin")
    }
}
