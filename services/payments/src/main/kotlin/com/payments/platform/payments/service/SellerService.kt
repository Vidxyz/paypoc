package com.payments.platform.payments.service

import com.payments.platform.payments.persistence.SellerStripeAccountEntity
import com.payments.platform.payments.persistence.SellerStripeAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Service for managing seller Stripe account information.
 * 
 * Handles business logic for seller Stripe account operations.
 */
@Service
class SellerService(
    private val sellerStripeAccountRepository: SellerStripeAccountRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    /**
     * Gets all Stripe accounts for a seller.
     * 
     * @param sellerId The seller ID (typically the seller's email)
     * @return List of Stripe account entities for the seller
     */
    fun getSellerStripeAccounts(sellerId: String): List<SellerStripeAccountEntity> {
        logger.debug("Fetching Stripe accounts for seller: $sellerId")
        return sellerStripeAccountRepository.findBySellerId(sellerId)
    }
    
    /**
     * Updates or creates a seller's Stripe account ID for a specific currency.
     * 
     * Business rules:
     * - Validates Stripe account ID format (must start with "acct_")
     * - Creates new entry if one doesn't exist for the currency
     * - Updates existing entry if one already exists
     * 
     * @param sellerId The seller ID (typically the seller's email)
     * @param currency The currency code (e.g., "USD")
     * @param stripeAccountId The Stripe account ID (must start with "acct_")
     * @return The updated or created Stripe account entity
     * @throws IllegalArgumentException if Stripe account ID format is invalid
     */
    @Transactional
    fun updateStripeAccount(
        sellerId: String,
        currency: String,
        stripeAccountId: String
    ): SellerStripeAccountEntity {
        logger.debug("Updating Stripe account for seller: $sellerId, currency: $currency, accountId: $stripeAccountId")
        
        // Validate Stripe account ID format
        if (!stripeAccountId.startsWith("acct_")) {
            throw IllegalArgumentException("Stripe account ID must start with 'acct_'")
        }
        
        // Find existing account or create new one
        val existingAccount = sellerStripeAccountRepository.findBySellerIdAndCurrency(sellerId, currency)
        
        val account = if (existingAccount != null) {
            logger.debug("Updating existing Stripe account entry for seller: $sellerId, currency: $currency")
            // Update existing account - create new entity with updated values
            val updated = SellerStripeAccountEntity(
                sellerId = existingAccount.sellerId,
                currency = existingAccount.currency,
                stripeAccountId = stripeAccountId,
                createdAt = existingAccount.createdAt,
                updatedAt = Instant.now()
            )
            sellerStripeAccountRepository.save(updated)
        } else {
            logger.debug("Creating new Stripe account entry for seller: $sellerId, currency: $currency")
            // Create new account entry
            val newAccount = SellerStripeAccountEntity(
                sellerId = sellerId,
                currency = currency,
                stripeAccountId = stripeAccountId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            sellerStripeAccountRepository.save(newAccount)
        }
        
        logger.info("Successfully updated Stripe account for seller: $sellerId, currency: $currency")
        return account
    }
}

