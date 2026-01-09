package com.payments.platform.ledger.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Health check endpoints for Kubernetes liveness and readiness probes.
 */
@RestController
@RequestMapping
class HealthController {
    
    /**
     * GET /health
     * Simple health check endpoint.
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }
    
    /**
     * GET /healthz
     * Health check endpoint (alternative path, commonly used in Kubernetes).
     */
    @GetMapping("/healthz")
    fun healthz(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }
}

