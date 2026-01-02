package com.payments.platform.payments.config

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
                    .title("Payments Service API")
                    .version("1.0.0")
                    .description(
                        """
                        The Payments Service is an orchestration layer that coordinates payment workflows.
                        
                        **Key Principles:**
                        - **Ledger-First Approach**: Payments always calls the Ledger Service BEFORE creating a payment record
                        - **Workflow State Only**: Payments database stores orchestration state, not financial truth
                        - **No Balance Computation**: Payments never computes balances - it always delegates to Ledger
                        - **State Machine Enforcement**: Payment state transitions are explicitly defined and enforced
                        
                        **Critical Invariant:**
                        > If payment exists, money exists.
                        
                        This is enforced by the ledger-first approach:
                        - If ledger rejects → payment is NOT created
                        - If ledger accepts → payment is created with ledger_transaction_id
                        
                        **Architecture:**
                        Payments Service orchestrates workflows but is NOT a source of financial truth.
                        The Ledger Service is the system of record for all financial transactions.
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

