package com.example.ihk.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ProcessStarter}: logs the routing decision and starts
 * nothing. Starting a real process is delegated to this organization's own
 * process-starting library, which is out of scope for both fitko-spring and
 * this sample - replace it by declaring your own {@code @Bean} of type
 * {@link ProcessStarter} (see {@link ProcessStarterConfiguration}).
 */
public class NoopProcessStarter implements ProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(NoopProcessStarter.class);

    @Override
    public void start(ProcessStartRequest request) {
        log.info("[noop] would start process '{}' for submission {} (case {}, tenant {}, Leistung {}) "
                        + "- wire a real ProcessStarter bean to actually start it",
                request.processKey(), request.submissionId(), request.caseId(), request.tenant(), request.leikaSchluessel());
    }
}
