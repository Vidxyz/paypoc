package com.payments.platform.inventory.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Inventory Service API")
                    .version("1.0.0")
                    .description(
                        """
                        The Inventory Service manages stock levels and reservations for products.
                        
                        **Key Features:**
                        - Stock tracking (available and reserved quantities)
                        - Stock reservations for orders
                        - Automatic reservation expiration
                        - SERIALIZABLE isolation for correctness
                        - Kafka event publishing for stock changes
                        
                        **Stock Management:**
                        - Available quantity: Stock available for reservation
                        - Reserved quantity: Stock reserved for pending orders
                        - Total quantity: Available + Reserved
                        
                        **Reservations:**
                        - Created when order is placed
                        - Committed when order is confirmed
                        - Released when order is cancelled
                        - Automatically expired after timeout
                        
                        **Critical Invariant:**
                        > Stock operations use SERIALIZABLE isolation to prevent race conditions
                        > and ensure stock correctness under concurrent access.
                        """.trimIndent()
                    )
                    .contact(
                        Contact()
                            .name("Payments Platform Team")
                    )
                    .license(
                        License()
                            .name("Proprietary")
                    )
            )
    }
}

