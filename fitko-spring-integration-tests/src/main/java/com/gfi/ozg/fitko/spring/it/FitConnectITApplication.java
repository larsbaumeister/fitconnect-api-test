package com.gfi.ozg.fitko.spring.it;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application the integration tests run against. Deliberately
 * empty: a bare {@code @SpringBootApplication} is exactly what a downstream
 * service is - the whole FIT-Connect integration comes from
 * {@code fitko-spring} being on the classpath and being picked up through
 * its {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * driven purely by {@code fitconnect.*} properties.
 *
 * <p>Every {@code *IT} test resolves this class as its
 * {@code @SpringBootConfiguration} automatically and adds its own beans
 * (recording listeners, ...) via a nested {@code @TestConfiguration}.
 *
 * <p>It also has a {@code main} method so the harness can be started as a
 * real application for manual exploration:
 * <pre>{@code
 * FITCONNECT_SENDER_CLIENT_ID=... (see README) \
 *   mvn -pl fitko-spring-integration-tests \
 *       -Dspring-boot.run.main-class=com.gfi.ozg.fitko.spring.it.FitConnectITApplication \
 *       exec:java
 * }</pre>
 */
@SpringBootApplication
public class FitConnectITApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitConnectITApplication.class, args);
    }
}
