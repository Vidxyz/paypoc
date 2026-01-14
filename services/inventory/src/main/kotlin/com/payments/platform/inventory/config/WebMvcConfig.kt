package com.payments.platform.inventory.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val authenticationInterceptor: AuthenticationInterceptor
) : WebMvcConfigurer {
    
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authenticationInterceptor)
            .addPathPatterns("/api/inventory/**")
            .excludePathPatterns("/health", "/healthz", "/api/docs/**", "/v3/api-docs/**")
    }
}

