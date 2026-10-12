package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Re-checks the status of every pending submission on a fixed interval. The
 * pending list is snapshotted on the event thread, the checks run on the
 * poller thread, and each result is handed back to the event thread.
 */
public class StatusPoller {

    private static final Logger log = LoggerFactory.getLogger(StatusPoller.class);

    private final SenderService senderService;
    private final Supplier<List<SubmissionRecord>> pendingSupplier;
    private final BiConsumer<SubmissionRecord, SenderService.StatusResult> resultHandler;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "status-poller");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> task;

    /**
     * @param pendingSupplier called on the event thread: the records to check
     * @param resultHandler   called on the event thread with each result
     */
    public StatusPoller(SenderService senderService,
                        Supplier<List<SubmissionRecord>> pendingSupplier,
                        BiConsumer<SubmissionRecord, SenderService.StatusResult> resultHandler) {
        this.senderService = senderService;
        this.pendingSupplier = pendingSupplier;
        this.resultHandler = resultHandler;
    }

    /** (Re)starts polling; {@code 0} or less stops it. */
    public synchronized void setInterval(int seconds) {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (seconds > 0) {
            task = executor.scheduleWithFixedDelay(this::pollOnce, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    /** Checks the given records now, on the poller thread. */
    public void checkNow(List<SubmissionRecord> records) {
        executor.execute(() -> check(records));
    }

    private void pollOnce() {
        try {
            List<SubmissionRecord> pending = onEventThread(pendingSupplier);
            if (!pending.isEmpty()) {
                log.debug("Polling status of {} pending submission(s)", pending.size());
                check(pending);
            }
        } catch (RuntimeException e) {
            log.warn("Status poll failed", e);
        }
    }

    private void check(List<SubmissionRecord> records) {
        for (SubmissionRecord record : records) {
            if (!record.wasSent()) {
                continue;
            }
            SenderService.StatusResult result = senderService.status(record);
            SwingUtilities.invokeLater(() -> resultHandler.accept(record, result));
        }
    }

    private static <T> T onEventThread(Supplier<T> supplier) {
        Object[] holder = new Object[1];
        try {
            SwingUtilities.invokeAndWait(() -> holder[0] = supplier.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        @SuppressWarnings("unchecked")
        T value = (T) holder[0];
        return value;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
