package com.payments.platform.payments.config

import com.payments.platform.payments.domain.ChargebackStateMachine
import com.payments.platform.payments.domain.PaymentStateMachine
import com.payments.platform.payments.domain.PayoutStateMachine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PaymentStateMachineConfig {

    @Bean
    fun paymentStateMachine(): PaymentStateMachine {
        return PaymentStateMachine()
    }

    @Bean
    fun payoutStateMachine(): PayoutStateMachine {
        return PayoutStateMachine()
    }

    @Bean
    fun chargebackStateMachine(): ChargebackStateMachine {
        return ChargebackStateMachine()
    }
}

