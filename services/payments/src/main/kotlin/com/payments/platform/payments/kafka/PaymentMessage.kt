package com.payments.platform.payments.kafka

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Base message structure for all payment-related Kafka messages.
 * All messages must include these fields for idempotency and traceability.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes(
    JsonSubTypes.Type(value = AuthorizePaymentCommand::class, name = "AUTHORIZE_PAYMENT"),
    JsonSubTypes.Type(value = CapturePaymentCommand::class, name = "CAPTURE_PAYMENT"),
    JsonSubTypes.Type(value = RetryPaymentStepCommand::class, name = "RETRY_PAYMENT_STEP"),
    JsonSubTypes.Type(value = PaymentAuthorizedEvent::class, name = "PAYMENT_AUTHORIZED"),
    JsonSubTypes.Type(value = PaymentFailedEvent::class, name = "PAYMENT_FAILED"),
    JsonSubTypes.Type(value = PaymentCapturedEvent::class, name = "PAYMENT_CAPTURED")
)
sealed class PaymentMessage(
    open val eventId: UUID,
    open val paymentId: UUID,
    open val idempotencyKey: String,
    open val type: String,
    open val attempt: Int,
    open val createdAt: Instant,
    open val payload: Map<String, Any>
)

// ============================================================================
// COMMANDS (Request work)
// ============================================================================

data class AuthorizePaymentCommand(
    override val eventId: UUID = UUID.randomUUID(),
    override val paymentId: UUID,
    override val idempotencyKey: String,
    override val attempt: Int = 1,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(eventId, paymentId, idempotencyKey, "AUTHORIZE_PAYMENT", attempt, createdAt, payload)

data class CapturePaymentCommand(
    override val eventId: UUID = UUID.randomUUID(),
    override val paymentId: UUID,
    override val idempotencyKey: String,
    override val attempt: Int = 1,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(eventId, paymentId, idempotencyKey, "CAPTURE_PAYMENT", attempt, createdAt, payload)

data class RetryPaymentStepCommand(
    override val eventId: UUID = UUID.randomUUID(),
    override val paymentId: UUID,
    override val idempotencyKey: String,
    val originalType: String, // The original command type to retry
    override val attempt: Int,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(eventId, paymentId, idempotencyKey, "RETRY_PAYMENT_STEP", attempt, createdAt, payload)

// ============================================================================
// EVENTS (Announce facts)
// ============================================================================

data class PaymentAuthorizedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val paymentId: UUID,
    override val idempotencyKey: String,
    override val attempt: Int = 1,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(eventId, paymentId, idempotencyKey, "PAYMENT_AUTHORIZED", attempt, createdAt, payload)

data class PaymentFailedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val paymentId: UUID,
    override val idempotencyKey: String,
    val reason: String,
    override val attempt: Int = 1,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(eventId, paymentId, idempotencyKey, "PAYMENT_FAILED", attempt, createdAt, payload)

/**
 * PaymentCapturedEvent - published after Stripe webhook confirms payment capture.
 * 
 * This event triggers the ledger write (double-entry bookkeeping).
 * Contains all information needed for ledger entry creation.
 */
data class PaymentCapturedEvent(
    override val eventId: UUID = UUID.randomUUID(),
    override val paymentId: UUID,
    override val idempotencyKey: String,
    val buyerId: String,
    val sellerId: String,
    val grossAmountCents: Long,
    val platformFeeCents: Long,
    val netSellerAmountCents: Long,
    val currency: String,
    val stripePaymentIntentId: String,
    override val attempt: Int = 1,
    override val createdAt: Instant = Instant.now(),
    override val payload: Map<String, Any> = emptyMap()
) : PaymentMessage(eventId, paymentId, idempotencyKey, "PAYMENT_CAPTURED", attempt, createdAt, payload)
