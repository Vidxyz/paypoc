package com.payments.platform.inventory.config

import com.payments.platform.inventory.auth.JwtValidator
import com.payments.platform.inventory.auth.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class AuthenticationInterceptor(
    private val jwtValidator: JwtValidator
) : HandlerInterceptor {
    
    companion object {
        const val USER_ATTRIBUTE = "currentUser"
    }
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val authHeader = request.getHeader("Authorization")
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.writer.write("{\"error\": \"Missing or invalid Authorization header\"}")
            return false
        }
        
        val token = authHeader.substring(7)
        val user = jwtValidator.validateAndExtractUser(token)
        
        if (user == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.writer.write("{\"error\": \"Invalid or expired token\"}")
            return false
        }
        
        request.setAttribute(USER_ATTRIBUTE, user)
        return true
    }
}

