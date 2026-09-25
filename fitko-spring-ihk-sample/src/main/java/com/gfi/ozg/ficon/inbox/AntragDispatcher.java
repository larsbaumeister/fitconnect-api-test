package com.gfi.ozg.ficon.inbox;

import com.gfi.ozg.ficon.processstarter.ProcessStartRejectedException;
import com.gfi.ozg.ficon.processstarter.ProcessStartRequest;
import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.ProcessStarterLookup;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import com.gfi.ozg.ficon.processstarter.ProcessStarterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The read side of the inbox: periodically hands every due {@link
 * InboxStatus#PENDING} submission to the {@link ProcessStarter} configured
 * for its (tenant, Leistung) - resolved at dispatch time, so a routing change
 * applies to submissions already waiting.
 *
 * <p><b>One transaction per submission</b>, holding a row lock ({@link
 * InboxSubmissionRepository#findByIdForUpdate}) around {@link
 * ProcessStarter#start} and the status update. A {@code ProcessStarter}
 * whose work joins that transaction (e.g. embedded Camunda 7 on the same
 * DataSource) therefore starts the process <em>and</em> marks the row
 * {@code STARTED} atomically - a crash in between can neither lose nor
 * duplicate the start. A {@code ProcessStarter} calling out to something
 * remote cannot be covered by this transaction and must still be idempotent
 * on {@code submissionId} itself. The row lock is also what keeps several
 * replicas from starting the same submission twice.
 *
 * <p>Outcome of {@code start}:
 * <ul>
 *   <li>returns a {@link StartedProcess} - {@link InboxStatus#STARTED}, with
 *       which process was started and when</li>
 *   <li>throws {@link ProcessStartRejectedException} - {@link
 *       InboxStatus#REJECTED}, not retried</li>
 *   <li>throws anything else - retried after {@code antrag-dispatch.retry-delay}
 *       (doubling), {@link InboxStatus#FAILED} after {@code max-attempts}</li>
 * </ul>
 * On either exception the start's transaction is rolled back first, and the
 * outcome is recorded in a fresh one.
 */
@Component
public class AntragDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AntragDispatcher.class);

    private final InboxSubmissionRepository repository;
    private final ProcessStarterResolver resolver;
    private final ProcessStarterLookup processStarters;
    private final TransactionTemplate transactions;
    private final AntragDispatchProperties properties;
    private final Clock clock;

    public AntragDispatcher(InboxSubmissionRepository repository, ProcessStarterResolver resolver,
                            ProcessStarterLookup processStarters, TransactionTemplate transactions,
                            AntragDispatchProperties properties, Clock clock) {
        this.repository = repository;
        this.resolver = resolver;
        this.processStarters = processStarters;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${antrag-dispatch.interval:10s}", fixedDelayString = "${antrag-dispatch.interval:10s}")
    void scheduledRun() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            // e.g. the database is unreachable - try again next run.
            log.warn("Antrag dispatch run failed, will retry next run", e);
        }
    }

    /**
     * Runs one dispatch pass synchronously.
     *
     * @return how many submissions were attempted
     */
    public int dispatchDue() {
        List<UUID> due = repository.findDueIds(clock.instant(), PageRequest.of(0, properties.getBatchSize()));
        if (!due.isEmpty()) {
            log.debug("Dispatching {} due submission(s)", due.size());
        }
        due.forEach(this::dispatchOne);
        return due.size();
    }

    private void dispatchOne(UUID submissionId) {
        // Set inside the start transaction, read after it - even after a rollback.
        AtomicReference<String> processStarterClass = new AtomicReference<>();
        try {
            transactions.executeWithoutResult(status -> start(submissionId, processStarterClass));
        } catch (ProcessStartRejectedException e) {
            log.warn("{} rejected submission {}: {}", processStarterClass.get(), submissionId, e.getMessage());
            recordOutcome(submissionId, submission ->
                    submission.markRejected(processStarterClass.get(), e.getMessage(), clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Starting a process for submission {} failed", submissionId, e);
            recordOutcome(submissionId, submission -> {
                submission.recordFailure(processStarterClass.get(), describe(e), clock.instant(),
                        properties.retryDelayAfter(submission.getAttempts() + 1), properties.getMaxAttempts());
                if (submission.getStatus() == InboxStatus.FAILED) {
                    log.error("Submission {} failed {} time(s), giving up - needs manual intervention",
                            submissionId, submission.getAttempts());
                }
            });
        }
    }

    private void start(UUID submissionId, AtomicReference<String> processStarterClass) {
        Instant now = clock.instant();
        InboxSubmission submission = repository.findByIdForUpdate(submissionId).orElse(null);
        if (submission == null || !submission.isDue(now)) {
            // Handled by another replica between findDueIds and the lock.
            return;
        }
        String className = resolver
                .resolveProcessStarterClassName(submission.getTenant(), submission.getServiceIdentifier())
                .orElseThrow(() -> new IllegalStateException("No ProcessStarter mapped for tenant "
                        + submission.getTenant() + " / Leistung " + submission.getServiceIdentifier()));
        processStarterClass.set(className);
        ProcessStarter processStarter = processStarters.resolve(className);

        StartedProcess process = processStarter.start(new ProcessStartRequest(submission));
        if (process == null) {
            // Rolls back like any other failure - see ProcessStarter#start.
            throw new IllegalStateException(className + ".start() returned null instead of a StartedProcess");
        }

        submission.markStarted(className, process, clock.instant());
        log.info("Started process {} (instance {}) for submission {} via {}", process.processDefinition(),
                process.instanceId().orElse("-"), submissionId, className);
    }

    private void recordOutcome(UUID submissionId, Consumer<InboxSubmission> update) {
        try {
            transactions.executeWithoutResult(status -> repository.findByIdForUpdate(submissionId)
                    .filter(submission -> submission.getStatus() == InboxStatus.PENDING)
                    .ifPresent(update));
        } catch (RuntimeException e) {
            // Stays PENDING and due - simply attempted again next run.
            log.error("Could not record the dispatch outcome of submission {}", submissionId, e);
        }
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getName() + ": " + e.getMessage();
    }
}
