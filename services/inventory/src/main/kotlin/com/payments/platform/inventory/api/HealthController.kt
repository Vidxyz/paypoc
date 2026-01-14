package com.payments.platform.inventory.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class HealthController {
    
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }
    
    @GetMapping("/healthz")
    fun healthz(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }
}

