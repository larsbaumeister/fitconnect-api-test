package com.example.gewerbeamt.receive;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stand-in for whatever a real receiver would persist submissions into (a
 * database, a queue, a case-management system). In memory, keyed by
 * submission id.
 *
 * <p>Keyed by submission id <em>on purpose</em>: FIT-Connect delivery is
 * at-least-once and this starter re-publishes an unresolved submission on
 * every poll cycle, so {@link #save} has to be idempotent. See
 * {@link GewerbeanmeldungHandler}.
 */
@Component
public class ReceivedAntragStore {

    private final ConcurrentMap<UUID, ReceivedGewerbeanmeldung> bySubmissionId = new ConcurrentHashMap<>();

    /** @return {@code true} if this was the first time we saw this submission */
    public boolean save(ReceivedGewerbeanmeldung antrag) {
        return bySubmissionId.putIfAbsent(antrag.submissionId(), antrag) == null;
    }

    public boolean contains(UUID submissionId) {
        return bySubmissionId.containsKey(submissionId);
    }

    public List<ReceivedGewerbeanmeldung> all() {
        return bySubmissionId.values().stream()
                .sorted((a, b) -> b.receivedAt().compareTo(a.receivedAt()))
                .toList();
    }
}
