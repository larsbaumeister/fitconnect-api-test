package com.gfi.ozg.ficon;

import com.gfi.ozg.ficon.processstarter.ProcessStarterRoutingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProcessStarterRoutingProperties.class)
public class IhkAntragRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(IhkAntragRouterApplication.class, args);
    }
}
