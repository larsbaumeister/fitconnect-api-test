package com.gfi.ozg.fitko.spring.it.support;

import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;
import com.gfi.ozg.fitko.spring.receive.SubmissionReceivedEvent;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.event.problems.data.DataSchemaViolation;
import dev.fitko.fitconnect.api.domain.model.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A configurable {@code @EventListener} for {@link SubmissionReceivedEvent}
 * shared by the round-trip tests. For each event it:
 *
 * <ol>
 *   <li>takes a {@link Received} snapshot (data, attachments, metadata,
 *       service id, ...) that stays readable after the poll cycle, and
 *       counts how many times that submission id has been seen, then</li>
 *   <li>optionally accepts/rejects it <em>inline</em> - gated by
 *       {@link #resolveWhen(Predicate)} so that only the test's own
 *       submission is ever resolved and foreign submissions on the shared
 *       destination are left alone.</li>
 * </ol>
 *
 * Resolving inline (not "await, then accept the stored object later") is
 * deliberate: {@code accept()/reject()} are single-use and tied to the
 * download they came from.
 */
public class RecordingListener {

    private static final Logger log = LoggerFactory.getLogger(RecordingListener.class);

    public enum Resolution { LEAVE, ACCEPT, REJECT }

    /** A read-only snapshot of one delivery, safe to assert on after the poll cycle. */
    public record Received(
            UUID submissionId,
            UUID caseId,
            UUID destinationId,
            String serviceId,
            String region,
            String dataMimeType,
            String data,
            List<Attachment> attachments,
            Metadata metadata,
            int sighting) {
    }

    private final Map<UUID, Received> lastBySubmission = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> sightings = new ConcurrentHashMap<>();
    private final List<UUID> arrivalOrder = new CopyOnWriteArrayList<>();

    private volatile Resolution resolution = Resolution.LEAVE;
    private volatile Predicate<Received> resolveWhen = r -> true;
    private volatile List<Problem> rejectProblems = List.of(new DataSchemaViolation());
    private volatile Consumer<IncomingSubmission> onEach = s -> { };

    @EventListener
    public void onSubmission(SubmissionReceivedEvent event) {
        IncomingSubmission submission = event.getSubmission();
        int sighting = sightings.computeIfAbsent(submission.getSubmissionId(), k -> new AtomicInteger())
                .incrementAndGet();
        Received received = snapshot(submission, sighting);
        lastBySubmission.put(received.submissionId(), received);
        arrivalOrder.add(received.submissionId());
        log.info("Recorded submission {} (sighting #{}, marker={})",
                received.submissionId(), sighting, Payloads.findMarker(received.data()).orElse("<none>"));

        try {
            onEach.accept(submission);
        } catch (RuntimeException e) {
            log.warn("onEach callback threw for submission {}", received.submissionId(), e);
        }

        if (!resolveWhen.test(received) || submission.isResolved()) {
            return;
        }
        switch (resolution) {
            case ACCEPT -> submission.accept();
            case REJECT -> submission.reject(rejectProblems);
            case LEAVE -> { }
        }
    }

    private static Received snapshot(IncomingSubmission s, int sighting) {
        String serviceId = null;
        try {
            serviceId = s.getServiceType() == null ? null : s.getServiceType().getIdentifier();
        } catch (RuntimeException ignored) {
            // service type not populated on this submission
        }
        String mimeType = null;
        try {
            mimeType = s.getDataMimeType();
        } catch (RuntimeException ignored) {
            // no content structure / mime type
        }
        String region = null;
        try {
            region = s.getRegion().orElse(null);
        } catch (RuntimeException ignored) {
            // no region on this submission
        }
        return new Received(
                s.getSubmissionId(), s.getCaseId(), s.getDestinationId(),
                serviceId, region, mimeType, s.getDataAsString(),
                s.getAttachments(), s.getMetadata(), sighting);
    }

    // --- configuration (call before the submission arrives) -------------------

    public RecordingListener resolution(Resolution resolution) {
        this.resolution = resolution;
        return this;
    }

    public RecordingListener resolveWhen(Predicate<Received> predicate) {
        this.resolveWhen = predicate;
        return this;
    }

    public RecordingListener rejectWith(Problem... problems) {
        this.rejectProblems = List.of(problems);
        return this;
    }

    public RecordingListener onEach(Consumer<IncomingSubmission> callback) {
        this.onEach = callback;
        return this;
    }

    /** Shorthand: accept exactly the submission with this id, leave everything else. */
    public RecordingListener acceptOnly(UUID submissionId) {
        return resolution(Resolution.ACCEPT).resolveWhen(r -> r.submissionId().equals(submissionId));
    }

    /** Shorthand: reject exactly the submission with this id (with the given problems), leave everything else. */
    public RecordingListener rejectOnly(UUID submissionId, Problem... problems) {
        return resolution(Resolution.REJECT).rejectWith(problems).resolveWhen(r -> r.submissionId().equals(submissionId));
    }

    /**
     * Shorthand: accept whichever submission carries {@code marker} in its data, leave everything else.
     * Race-free - set this before {@code send()}, since the marker is known up front while the id is not.
     */
    public RecordingListener acceptMarked(String marker) {
        return resolution(Resolution.ACCEPT).resolveWhen(r -> r.data() != null && r.data().contains(marker));
    }

    /** Shorthand: reject whichever submission carries {@code marker} in its data, leave everything else. */
    public RecordingListener rejectMarked(String marker, Problem... problems) {
        return resolution(Resolution.REJECT).rejectWith(problems)
                .resolveWhen(r -> r.data() != null && r.data().contains(marker));
    }

    public void reset() {
        lastBySubmission.clear();
        sightings.clear();
        arrivalOrder.clear();
        resolution = Resolution.LEAVE;
        resolveWhen = r -> true;
        rejectProblems = List.of(new DataSchemaViolation());
        onEach = s -> { };
    }

    // --- queries -------------------------------------------------------------

    public boolean sawId(UUID submissionId) {
        return lastBySubmission.containsKey(submissionId);
    }

    public int timesSeen(UUID submissionId) {
        AtomicInteger c = sightings.get(submissionId);
        return c == null ? 0 : c.get();
    }

    public Optional<Received> received(UUID submissionId) {
        return Optional.ofNullable(lastBySubmission.get(submissionId));
    }

    public Received require(UUID submissionId) {
        return received(submissionId).orElseThrow(
                () -> new AssertionError("No SubmissionReceivedEvent recorded for " + submissionId));
    }

    public List<UUID> arrivalOrder() {
        return List.copyOf(arrivalOrder);
    }
}
