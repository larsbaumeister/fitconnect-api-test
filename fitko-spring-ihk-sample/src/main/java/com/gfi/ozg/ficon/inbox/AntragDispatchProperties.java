package com.gfi.ozg.ficon.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code antrag-dispatch.*} - how {@link AntragDispatcher} works through the inbox. */
@ConfigurationProperties(prefix = "antrag-dispatch")
public class AntragDispatchProperties {

    /** Set to {@code false} to stop dispatching (submissions are still received and stored). */
    private boolean enabled = true;

    /** Delay between the end of one dispatch run and the start of the next. */
    private Duration interval = Duration.ofSeconds(10);

    /** Max submissions handled per run. */
    private int batchSize = 50;

    /** Attempts (including the first) before a submission is marked {@code FAILED}. */
    private int maxAttempts = 10;

    /** Delay before the first retry; doubles on every further failure. */
    private Duration retryDelay = Duration.ofSeconds(30);

    /** Upper bound for the doubling {@link #retryDelay}. */
    private Duration maxRetryDelay = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Duration getMaxRetryDelay() {
        return maxRetryDelay;
    }

    public void setMaxRetryDelay(Duration maxRetryDelay) {
        this.maxRetryDelay = maxRetryDelay;
    }

    /** {@link #retryDelay} doubled per failure already recorded, capped at {@link #maxRetryDelay}. */
    Duration retryDelayAfter(int failedAttempts) {
        Duration delay = retryDelay;
        for (int i = 1; i < failedAttempts && delay.compareTo(maxRetryDelay) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
    }
}
