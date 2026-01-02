package com.payments.platform.payments.config

import com.payments.platform.payments.domain.PaymentStateMachine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PaymentStateMachineConfig {
    
    @Bean
    fun paymentStateMachine(): PaymentStateMachine {
        return PaymentStateMachine()
    }
}

