package com.gfi.ozg.ficon.support;

import com.gfi.ozg.ficon.processstarter.ProcessStarter;
import com.gfi.ozg.ficon.processstarter.StartedProcess;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A {@link ProcessStarter} whose outcome a test sets, recording every call. */
public class ControllableProcessStarter implements ProcessStarter {

    public final List<UUID> started = new CopyOnWriteArrayList<>();
    public final List<List<String>> attachmentFileNames = new CopyOnWriteArrayList<>();
    public volatile Function<ReceivedSubmission, StartedProcess> behaviour = ControllableProcessStarter::succeed;

    public void reset() {
        started.clear();
        attachmentFileNames.clear();
        behaviour = ControllableProcessStarter::succeed;
    }

    public static StartedProcess succeed(ReceivedSubmission submission) {
        return new StartedProcess("ausbildungsvertrag-eintragung", "instance-" + submission.getSubmissionId());
    }

    @Override
    public StartedProcess start(ReceivedSubmission submission, String tenant) {
        started.add(submission.getSubmissionId());
        attachmentFileNames.add(submission.getAttachments().stream().map(Attachment::getFileName).toList());
        return behaviour.apply(submission);
    }
}
