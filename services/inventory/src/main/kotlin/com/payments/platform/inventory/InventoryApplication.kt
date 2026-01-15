package com.payments.platform.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableKafka
@EnableRetry
@EnableScheduling
class InventoryApplication

fun main(args: Array<String>) {
    runApplication<InventoryApplication>(*args)
}

