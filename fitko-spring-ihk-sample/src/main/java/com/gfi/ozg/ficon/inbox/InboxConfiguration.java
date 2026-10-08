package com.gfi.ozg.ficon.inbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class InboxConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
