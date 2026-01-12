package com.payments.platform.ledger.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Interceptor that validates service secret (opaque token) for /ledger routes.
 * 
 * These routes are intended for service-to-service communication only.
 */
@Component
class ServiceSecretInterceptor(
    @Value("\${ledger.service.api.token}") private val serviceApiToken: String
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Skip authentication for public endpoints
        if (isPublicEndpoint(request.requestURI)) {
            return true
        }
        
        // Only protect /ledger routes with service secret
        if (!request.requestURI.startsWith("/ledger")) {
            return true
        }
        
        // Extract bearer token from Authorization header
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("Service secret authentication failed for request: ${request.requestURI} - missing Authorization header")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Missing or invalid Authorization header. Service secret token required."}""")
            return false
        }
        
        val token = authHeader.substring(7) // Remove "Bearer " prefix
        
        // Validate service secret token
        if (token != serviceApiToken) {
            logger.warn("Service secret authentication failed for request: ${request.requestURI} - invalid token")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Invalid service secret token"}""")
            return false
        }
        
        logger.debug("Service secret authentication successful for request: ${request.requestURI}")
        return true
    }
    
    private fun isPublicEndpoint(uri: String): Boolean {
        return uri.startsWith("/actuator") ||
               uri.startsWith("/health") ||
               uri.startsWith("/healthz") ||
               uri.startsWith("/api/docs") ||
               uri.startsWith("/swagger-ui") ||
               uri.startsWith("/v3/api-docs")
    }
}
