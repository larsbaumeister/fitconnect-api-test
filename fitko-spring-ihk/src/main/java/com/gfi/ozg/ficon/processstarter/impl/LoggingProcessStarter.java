package com.gfi.ozg.ficon.processstarter.impl;

import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder: starts nothing, only logs. Replace with real implementations in this package. */
@Component
public class LoggingProcessStarter implements ProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(LoggingProcessStarter.class);

    @Override
    public StartedProcess start(ReceivedSubmission submission, String tenant) {
        log.info("[logging-only] submission {} (case {}, tenant {}, Leistung {}, {} attachment(s))",
                submission.getSubmissionId(), submission.getCaseId(), tenant,
                submission.getServiceType().getIdentifier(), submission.getAttachments().size());
        return StartedProcess.withoutInstanceId("logging-only");
    }
}
