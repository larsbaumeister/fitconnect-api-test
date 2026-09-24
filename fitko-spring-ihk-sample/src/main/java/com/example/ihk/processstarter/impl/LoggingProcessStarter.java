package com.example.ihk.processstarter.impl;

import com.example.ihk.processstarter.ProcessStarter;
import com.example.ihk.processstarter.ProcessStartRequest;
import com.example.ihk.processstarter.ProcessStarterLookup;
import com.example.ihk.routing.AntragRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A second, deliberately distinct {@link ProcessStarter} implementation -
 * exists only to prove {@link ProcessStarterLookup} really dispatches to a
 * different class per (tenant, Leistung) rather than one shared instance
 * (see {@link AntragRoutingProperties#getProcessStarterByTenant()} in
 * {@code application.yaml}). Still a stub, same as {@link NoopProcessStarter} -
 * a real implementation here would call this organization's own
 * process-starting library for whichever Leistung it's wired to.
 */
@Component
public class LoggingProcessStarter implements ProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(LoggingProcessStarter.class);

    @Override
    public void start(ProcessStartRequest request) {
        var submission = request.submission();
        log.info("[logging-only] handling submission {} (case {}, tenant {}, Leistung {}, "
                        + "{} bytes of {} payload) with a dedicated ProcessStarter implementation "
                        + "- replace with a real one for this Leistung",
                submission.getSubmissionId(), submission.getCaseId(), request.tenant(),
                submission.getServiceType().getIdentifier(), submission.getDataAsBytes().length,
                submission.getDataMimeType());
    }
}
