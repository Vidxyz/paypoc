package com.payments.platform.ledger

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LedgerServiceApplication

fun main(args: Array<String>) {
    runApplication<LedgerServiceApplication>(*args)
}

