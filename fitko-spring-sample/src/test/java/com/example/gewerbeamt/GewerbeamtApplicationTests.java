package com.example.gewerbeamt;

import com.example.gewerbeamt.send.GewerbeanmeldungService;
import com.gfi.ozg.fitko.spring.send.SubmissionSender;
import dev.fitko.fitconnect.client.SenderClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context starts with {@code fitko-spring} on the classpath and wires an
 * {@link SubmissionSender} into {@link GewerbeanmeldungService}.
 *
 * <p>Receiving is switched off here so the test needs no destination keys;
 * the SDK's {@link SenderClient} is replaced by a Mockito mock so nothing
 * touches the network. Dummy credentials are enough for the sender
 * auto-configuration to build its beans. The destination id is overridden
 * with a literal value only so the (still bound, but unused) receiver
 * properties don't need the {@code FITCONNECT_DESTINATION_ID} env var
 * {@code application.yaml} otherwise expects.
 */
@SpringBootTest(properties = {
        "fitconnect.environment=TEST",
        "fitconnect.sender.client-id=test-client-id",
        "fitconnect.sender.client-secret=test-client-secret",
        "fitconnect.receiver.enabled=false",
        "fitconnect.receiver.destinations[0].id=9f6bb611-df46-494a-9a98-a253f1362dc7"
})
class GewerbeamtApplicationTests {

    @MockitoBean
    SenderClient senderClient;

    @Autowired
    SubmissionSender submissionSender;

    @Autowired
    GewerbeanmeldungService gewerbeanmeldungService;

    @Test
    void contextLoadsAndWiresTheStarterBeans() {
        assertThat(submissionSender).isNotNull();
        assertThat(gewerbeanmeldungService).isNotNull();
    }
}
