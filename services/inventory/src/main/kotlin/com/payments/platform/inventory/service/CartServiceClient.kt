package com.payments.platform.inventory.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import java.time.Duration
import java.util.UUID

@Component
class CartServiceClient(
    @Value("\${cart.service.url}") private val cartServiceUrl: String,
    @Value("\${cart.service.internal.api.token}") private val internalApiToken: String,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val webClient = WebClient.builder()
        .baseUrl(cartServiceUrl)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    /**
     * Check if a cart exists (by cart ID)
     * Returns true if cart exists, false if it doesn't, null on error (to be safe)
     */
    fun checkCartExists(cartId: UUID): Boolean? {
        return try {
            val response = webClient.get()
                .uri("/internal/carts/$cartId/exists")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $internalApiToken")
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(5))
                .retryWhen(Retry.backoff(1, Duration.ofMillis(100)))
                .block()

            if (response != null) {
                val responseBody = objectMapper.readTree(response)
                val exists = responseBody.get("exists")?.asBoolean() ?: false
                logger.debug("Cart $cartId exists check: $exists")
                exists
            } else {
                logger.warn("Empty response when checking cart existence for $cartId")
                null
            }
        } catch (e: WebClientResponseException.Unauthorized) {
            logger.error("Unauthorized when checking cart existence for $cartId")
            null // On auth error, assume cart exists to be safe
        } catch (e: WebClientResponseException.NotFound) {
            logger.debug("Cart $cartId not found")
            false
        } catch (e: WebClientResponseException) {
            logger.warn("Unexpected status ${e.statusCode} when checking cart existence for $cartId: ${e.responseBodyAsString}")
            null // On error, assume cart exists to be safe
        } catch (e: Exception) {
            logger.error("Error checking cart existence for $cartId: ${e.message}", e)
            null // On error, assume cart exists to be safe (don't release if uncertain)
        }
    }
}
