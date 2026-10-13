package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.Connection;
import dev.fitko.fitconnect.rest.model.submission.SentSubmission;
import dev.fitko.fitconnect.sdk.FitConnectSdk;
import dev.fitko.fitconnect.sdk.api.OutgoingSubmission;
import dev.fitko.fitconnect.sdk.api.crypto.EncryptionKey;
import dev.fitko.fitconnect.sdk.api.event.TransferLog;
import dev.fitko.fitconnect.sdk.clients.Cases;
import dev.fitko.fitconnect.sdk.clients.OnlineService;
import dev.fitko.fitconnect.sdk.clients.Organisation;
import dev.fitko.fitconnect.sdk.exceptions.FitConnectConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One SDK client per {@link Connection}, created on first use. It acts as the
 * connection's {@link Connection#fromDestinationId}, which the SDK sends as
 * {@code fromDestinationId} with every submission. {@link #invalidate} drops
 * a client after its connection was edited so the next call picks up the new
 * settings.
 */
public class SenderClients {

    private static final Logger log = LoggerFactory.getLogger(SenderClients.class);

    private final Map<String, Sender> clients = new ConcurrentHashMap<>();

    public Sender get(Connection connection) {
        return clients.computeIfAbsent(connection.id, id -> Sender.create(connection));
    }

    public void invalidate(String connectionId) {
        clients.remove(connectionId);
    }

    public void invalidateAll() {
        clients.clear();
    }

    /**
     * What this tool needs from a sending destination. The SDK hands out a
     * different client per destination type - {@link OnlineService} for a
     * type C online service, {@link Organisation} for a type A/B authority -
     * and both send submissions the same way.
     */
    public static final class Sender {

        private final FitConnectSdk sdk;
        private final Function<OutgoingSubmission, SentSubmission> send;
        private final Cases cases;

        private Sender(FitConnectSdk sdk, Function<OutgoingSubmission, SentSubmission> send, Cases cases) {
            this.sdk = sdk;
            this.send = send;
            this.cases = cases;
        }

        static Sender create(Connection connection) {
            UUID from = connection.fromDestinationId;
            if (from == null) {
                throw new IllegalStateException("Connection '" + connection.label + "' has no 'From destination' -"
                        + " set it under File → Connections… to one of the destinations assigned to this client");
            }
            FitConnectSdk sdk = SdkConfig.createSdk(connection);
            try {
                OnlineService service = sdk.onlineService(from);
                log.info("Connection '{}' sends as online service {}", connection.label, from);
                return new Sender(sdk, service::send, service.cases());
            } catch (FitConnectConfigurationException notAnOnlineService) {
                // Not type C - organisation(...) acts as a type A/B destination, and explains itself if that fails too.
                Organisation organisation = sdk.organisation(from);
                log.info("Connection '{}' sends as organisation {}", connection.label, from);
                return new Sender(sdk, organisation::send, organisation.cases());
            }
        }

        public SentSubmission send(OutgoingSubmission submission) {
            return send.apply(submission);
        }

        public TransferLog logOf(SentSubmission sent) {
            return cases.logOf(sent);
        }

        public EncryptionKey activeEncryptionKeyOf(UUID destinationId) {
            return sdk.directory().activeEncryptionKeyOf(destinationId);
        }
    }
}
