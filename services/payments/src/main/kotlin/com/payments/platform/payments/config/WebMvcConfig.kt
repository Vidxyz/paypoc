package com.payments.platform.payments.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val authenticationInterceptor: AuthenticationInterceptor
) : WebMvcConfigurer {
    
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authenticationInterceptor)
    }
    
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(
                "https://buyit.local",
                "https://admin.local",
                "https://seller.local",
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:3002",
                "http://localhost:5173"
            )
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
            .allowedHeaders("Accept", "Content-Type", "Authorization", "Origin", "X-Requested-With")
            .allowCredentials(true)
            .maxAge(3600)
    }
    
    /**
     * CORS filter bean to ensure CORS headers are applied even for OPTIONS preflight requests.
     * This works in conjunction with addCorsMappings but provides additional guarantee.
     */
    @Bean
    fun corsFilter(): CorsFilter {
        val source = UrlBasedCorsConfigurationSource()
        val config = CorsConfiguration().apply {
            allowedOrigins = listOf(
                "https://buyit.local",
                "https://admin.local",
                "https://seller.local",
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:3002",
                "http://localhost:5173"
            )
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
            allowedHeaders = listOf("Accept", "Content-Type", "Authorization", "Origin", "X-Requested-With")
            allowCredentials = true
            maxAge = 3600L
        }
        source.registerCorsConfiguration("/**", config)
        return CorsFilter(source)
    }
}

