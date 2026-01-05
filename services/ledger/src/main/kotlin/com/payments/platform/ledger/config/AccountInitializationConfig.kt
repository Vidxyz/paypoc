package com.payments.platform.ledger.config

import com.payments.platform.ledger.domain.AccountType
import com.payments.platform.ledger.service.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Initializes internal ledger accounts on application startup.
 * 
 * Creates system accounts that are needed for double-entry bookkeeping:
 * - STRIPE_CLEARING: Money received from Stripe
 * - BUYIT_REVENUE: Platform commission
 * - CHARGEBACK_CLEARING: For future chargeback handling
 * 
 * SELLER_PAYABLE accounts are created on-demand when needed (per seller).
 */
@Configuration
class AccountInitializationConfig(
    private val ledgerService: LedgerService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val SYSTEM_CURRENCIES = listOf("USD") // Can be extended to support multiple currencies
    
    @Bean
    fun initializeInternalAccounts(): CommandLineRunner {
        return CommandLineRunner {
            logger.info("Initializing internal ledger accounts...")
            
            for (currency in SYSTEM_CURRENCIES) {
                // STRIPE_CLEARING account (no reference_id - single account per currency)
                ensureAccountExists(
                    accountType = AccountType.STRIPE_CLEARING,
                    referenceId = null,
                    currency = currency,
                    accountId = UUID.nameUUIDFromBytes("STRIPE_CLEARING_$currency".toByteArray())
                )
                
                // BUYIT_REVENUE account (no reference_id - single account per currency)
                ensureAccountExists(
                    accountType = AccountType.BUYIT_REVENUE,
                    referenceId = null,
                    currency = currency,
                    accountId = UUID.nameUUIDFromBytes("BUYIT_REVENUE_$currency".toByteArray())
                )
                
                // CHARGEBACK_CLEARING account (no reference_id - single account per currency)
                ensureAccountExists(
                    accountType = AccountType.CHARGEBACK_CLEARING,
                    referenceId = null,
                    currency = currency,
                    accountId = UUID.nameUUIDFromBytes("CHARGEBACK_CLEARING_$currency".toByteArray())
                )
                
                // REFUNDS_CLEARING account (no reference_id - single account per currency)
                ensureAccountExists(
                    accountType = AccountType.REFUNDS_CLEARING,
                    referenceId = null,
                    currency = currency,
                    accountId = UUID.nameUUIDFromBytes("REFUNDS_CLEARING_$currency".toByteArray())
                )
            }
            
            logger.info("Internal ledger accounts initialized successfully")
        }
    }
    
    /**
     * Ensures an account exists, creating it if it doesn't.
     */
    private fun ensureAccountExists(
        accountType: AccountType,
        referenceId: String?,
        currency: String,
        accountId: UUID
    ) {
        try {
            val existing = ledgerService.findAccountByTypeAndReference(accountType, referenceId, currency)
            if (existing == null) {
                ledgerService.createAccount(accountId, accountType, referenceId, currency)
                logger.info("Created account: $accountType (reference_id=$referenceId, currency=$currency)")
            } else {
                logger.debug("Account already exists: $accountType (reference_id=$referenceId, currency=$currency)")
            }
        } catch (e: Exception) {
            logger.warn("Failed to create account $accountType (reference_id=$referenceId, currency=$currency): ${e.message}")
            // Don't fail startup if account creation fails - it might already exist from a previous run
        }
    }
}

