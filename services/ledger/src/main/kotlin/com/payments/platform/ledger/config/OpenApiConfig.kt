package com.payments.platform.ledger.config

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
                    .title("Ledger Service API")
                    .version("1.0.0")
                    .description(
                        """
                        The Ledger Service is the system of record for all financial transactions in the payments platform.
                        
                        **Key Features:**
                        - Append-only ledger with immutable transaction records
                        - No overdrafts: Balance checks prevent negative balances
                        - Idempotency: Unique idempotency keys prevent duplicate processing
                        - Concurrency safety: SERIALIZABLE isolation ensures correctness under concurrent access
                        - Atomicity: All transaction operations are atomic via database transactions
                        
                        **Transaction Isolation:**
                        All ledger operations use SERIALIZABLE isolation level to ensure financial correctness
                        under high concurrency. This may result in serialization failures that are automatically retried.
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

