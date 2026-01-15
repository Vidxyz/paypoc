package com.payments.platform.inventory.config

import com.payments.platform.inventory.auth.JwtValidator
import com.payments.platform.inventory.auth.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class AuthenticationInterceptor(
    private val jwtValidator: JwtValidator
) : HandlerInterceptor {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    companion object {
        const val USER_ATTRIBUTE = "currentUser"
    }
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // Allow OPTIONS requests (CORS preflight) without authentication
        if (request.method == "OPTIONS") {
            logger.debug("Allowing OPTIONS request for path: ${request.requestURI}")
            return true
        }
        
        val authHeader = request.getHeader("Authorization")
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("Missing or invalid Authorization header for path: ${request.requestURI}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("{\"error\": \"Missing or invalid Authorization header\"}")
            return false
        }
        
        val token = authHeader.substring(7)
        try {
            val user = jwtValidator.validateAndExtractUser(token)
            
            if (user == null) {
                logger.warn("JWT validation returned null for path: ${request.requestURI}")
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("{\"error\": \"Invalid or expired token\"}")
                return false
            }
            
            logger.debug("Successfully authenticated user: ${user.email} (${user.userId}) for path: ${request.requestURI}")
            request.setAttribute(USER_ATTRIBUTE, user)
            return true
        } catch (e: Exception) {
            logger.error("Unexpected error during authentication for path: ${request.requestURI}", e)
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("{\"error\": \"Authentication failed: ${e.message}\"}")
            return false
        }
    }
}

