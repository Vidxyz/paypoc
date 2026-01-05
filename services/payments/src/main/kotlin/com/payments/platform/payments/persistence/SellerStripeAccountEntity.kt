package com.payments.platform.payments.persistence

import jakarta.persistence.*
import java.time.Instant

/**
 * JPA entity for seller Stripe account mapping.
 * Maps seller_id to their Stripe connected account ID.
 * 
 * Supports multiple currencies per seller (composite primary key: seller_id + currency).
 */
@Entity
@Table(name = "seller_stripe_accounts")
@IdClass(SellerStripeAccountId::class)
class SellerStripeAccountEntity(
    @Id
    @Column(name = "seller_id", nullable = false)
    val sellerId: String,
    
    @Id
    @Column(name = "currency", nullable = false)
    val currency: String,
    
    @Column(name = "stripe_account_id", nullable = false)
    val stripeAccountId: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant
) {
    // JPA requires no-arg constructor
    constructor() : this(
        sellerId = "",
        currency = "",
        stripeAccountId = "",
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}

/**
 * Composite primary key for SellerStripeAccountEntity.
 */
data class SellerStripeAccountId(
    val sellerId: String = "",
    val currency: String = ""
) : java.io.Serializable

