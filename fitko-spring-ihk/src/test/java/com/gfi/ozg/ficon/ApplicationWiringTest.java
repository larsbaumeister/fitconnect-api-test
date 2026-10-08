package com.gfi.ozg.ficon;

import com.gfi.ozg.ficon.processstarter.ProcessStarterRouter;
import com.gfi.ozg.ficon.processstarter.impl.LoggingProcessStarter;
import com.gfi.ozg.ficon.receive.AntragPoller;
import com.gfi.ozg.ficon.support.ControllableProcessStarter;
import com.gfi.ozg.ficon.support.PostgresTestSupport;
import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.tools.keygen.TestKeyBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real wiring: SDK clients built from {@code fitconnect.*} with throwaway
 * keys (nothing is polled - the first poll would be in an hour), and the
 * routing from {@code application.yaml}.
 */
@TestPropertySource(properties = {
        "fitconnect.receiver.polling.enabled=true",
        "fitconnect.receiver.polling.initial-delay=1h",
        "fitconnect.receiver.client-id=test-client",
        "fitconnect.receiver.client-secret=test-secret",
        "fitconnect.sender.client-id=test-sender",
        "fitconnect.sender.client-secret=test-secret"
})
class ApplicationWiringTest extends PostgresTestSupport {

    @DynamicPropertySource
    static void destinations(DynamicPropertyRegistry registry) {
        for (String tenant : new String[] {"101-aachen", "133-hannover"}) {
            String prefix = "fitconnect.receiver.tenants." + tenant + ".destinations.antragseingang.";
            registry.add(prefix + "id", () -> UUID.randomUUID().toString());
            registry.add(prefix + "signing-key",
                    () -> "file:" + write(TestKeyBuilder.generateSignatureKeyPair().getPrivateKey()));
            registry.add(prefix + "decryption-keys[0]",
                    () -> "file:" + write(TestKeyBuilder.generateEncryptionKeyPair().getPrivateKey()));
        }
    }

    @Autowired
    AntragPoller poller;

    @Autowired
    SenderClient senderClient;

    @Autowired
    ProcessStarterRouter router;

    @Test
    void buildsTheSdkClients() {
        assertThat(poller).isNotNull();
        assertThat(senderClient).isNotNull();
    }

    @Test
    void routesByTenantAndLeistungFromApplicationYaml() {
        String mapped = "urn:de:fim:leika:leistung:99050035001000";

        assertThat(router.processStarterClassFor("101-aachen", mapped)).contains(LoggingProcessStarter.class.getName());
        // Same Leistung, other tenant, no mapping there - the default (overridden for tests).
        assertThat(router.processStarterClassFor("133-hannover", mapped)).contains(ControllableProcessStarter.class.getName());
        assertThat(router.processStarter(LoggingProcessStarter.class.getName())).isInstanceOf(LoggingProcessStarter.class);
    }

    private static Path write(JWK key) {
        try {
            Path file = Files.createTempFile("jwk", ".json");
            Files.writeString(file, key.toJSONString());
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
