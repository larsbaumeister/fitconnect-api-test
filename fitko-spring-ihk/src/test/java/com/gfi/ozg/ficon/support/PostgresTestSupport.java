package com.gfi.ozg.ficon.support;

import com.gfi.ozg.ficon.IhkApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application against a real PostgreSQL (Testcontainers), with polling
 * off - tests hand submissions to {@code AntragHandler} directly. Unmapped
 * Leistungen go to {@link ControllableProcessStarter}.
 */
@SpringBootTest(classes = IhkApplication.class, properties = {
        "fitconnect.receiver.polling.enabled=false",
        "fitconnect.receiver.tenants.101-aachen.destinations.antragseingang.id=00000000-0000-0000-0000-000000000101",
        "fitconnect.receiver.tenants.133-hannover.destinations.antragseingang.id=00000000-0000-0000-0000-000000000133",
        "antrag-routing.default-process-starter-class=com.gfi.ozg.ficon.support.ControllableProcessStarter"
})
@Import(PostgresTestSupport.Containers.class)
public abstract class PostgresTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    public static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Bean
        ControllableProcessStarter controllableProcessStarter() {
            return new ControllableProcessStarter();
        }
    }
}
