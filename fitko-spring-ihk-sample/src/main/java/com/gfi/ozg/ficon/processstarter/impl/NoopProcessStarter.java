package com.gfi.ozg.ficon.processstarter.impl;

import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.ProcessStartRequest;
import com.gfi.ozg.ficon.processstarter.ProcessStarterLookup;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A {@link ProcessStarter} that logs the routing decision and starts
 * nothing - useful both as an explicit "not implemented yet" placeholder for
 * one Leistung and as {@code antrag-routing.default-process-starter-class}.
 *
 * <p>A plain {@code @Component}, same as any other {@link ProcessStarter}
 * implementation you add - there is no single "the" default bean anymore
 * (see {@link ProcessStarterLookup}); this one is only ever used because
 * {@code application.yaml} names its fully-qualified class explicitly,
 * exactly like every other implementation.
 */
@Component
public class NoopProcessStarter implements ProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(NoopProcessStarter.class);

    @Override
    public StartedProcess start(ProcessStartRequest request) {
        var submission = request.submission();
        log.info("[noop] would start a process for submission {} (case {}, tenant {}, Leistung {}, "
                        + "{} bytes of {} payload, {} attachment(s)) - point antrag-routing at a real "
                        + "ProcessStarter implementation to actually start it",
                submission.getSubmissionId(), submission.getCaseId(), request.tenant(),
                submission.getServiceIdentifier(), submission.getDataAsBytes().length,
                submission.getDataMimeType(), submission.getAttachments().size());
        // Nothing is actually started - recorded as such, without an instance id.
        return StartedProcess.withoutInstanceId("noop");
    }
}
