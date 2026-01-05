package com.payments.platform.payments.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SellerStripeAccountRepository : JpaRepository<SellerStripeAccountEntity, SellerStripeAccountId> {
    /**
     * Finds a seller's Stripe account for a specific currency.
     * 
     * @param sellerId The seller ID
     * @param currency The currency code (e.g., "USD")
     * @return The seller's Stripe account entity, or null if not found
     */
    fun findBySellerIdAndCurrency(sellerId: String, currency: String): SellerStripeAccountEntity?
    
    /**
     * Finds all Stripe accounts for a seller (across all currencies).
     */
    fun findBySellerId(sellerId: String): List<SellerStripeAccountEntity>
}

