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
                        - **Ledger Writes After Money Movement**: Ledger writes only occur after Stripe webhook confirms payment capture (not at creation)
                        - **Workflow State Only**: Payments database stores orchestration state, not financial truth
                        - **No Balance Computation**: Payments never computes balances - it always delegates to Ledger
                        - **State Machine Enforcement**: Payment state transitions are explicitly defined and enforced
                        
                        **Critical Invariant:**
                        > If payment exists with ledger_transaction_id, money exists in ledger.
                        
                        The ledger write happens **after** Stripe confirms money movement via webhook:
                        - Payment creation: NO ledger write (no money has moved yet)
                        - Stripe webhook confirms capture: THEN write to ledger
                        - This ensures: "Ledger only records actual money movement"
                        
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

