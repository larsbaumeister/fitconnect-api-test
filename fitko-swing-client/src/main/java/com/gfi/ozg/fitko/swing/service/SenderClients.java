package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.Connection;
import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One SDK {@link SenderClient} per {@link Connection}, created on first use.
 * {@link #invalidate} drops a client after its connection was edited so the
 * next call picks up the new credentials.
 */
public class SenderClients {

    private final Map<String, SenderClient> clients = new ConcurrentHashMap<>();

    public SenderClient get(Connection connection) {
        return clients.computeIfAbsent(connection.id, id -> ClientFactory.createSenderClient(SdkConfig.create(connection)));
    }

    public void invalidate(String connectionId) {
        clients.remove(connectionId);
    }

    public void invalidateAll() {
        clients.clear();
    }
}
