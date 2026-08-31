package com.gfi.ozg.fitko.spring.send;

import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.api.domain.sender.SendableSubmission;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.DataStep;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.OptionalPropertiesStep;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.ServiceTypeStep;
import dev.fitko.fitconnect.api.exceptions.client.FitConnectSenderException;
import dev.fitko.fitconnect.client.SenderClient;
import com.gfi.ozg.fitko.spring.config.MetadataVersions;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds a {@link SendableSubmission} from an {@link SubmissionToSend} and hands
 * it to the SDK's {@link SenderClient}. Mirrors the CLI sender sample's
 * {@code SubmissionSubmitter}.
 */
@Slf4j
public class DefaultSubmissionSender implements SubmissionSender {

    private final SenderClient senderClient;

    public DefaultSubmissionSender(SenderClient senderClient) {
        this.senderClient = senderClient;
    }

    @Override
    public SentSubmission send(SubmissionToSend submissionToSend) {
        UUID destinationId = requireDestinationId(submissionToSend);
        SendableSubmission submission = buildSubmission(destinationId, submissionToSend);
        log.debug("Sending submission (service={}) to destination {}", submissionToSend.getServiceId(), destinationId);
        try {
            SentSubmission sent = senderClient.send(submission);
            log.debug("Sent submission to destination {}: submissionId={}, caseId={}",
                    destinationId, sent.getSubmissionId(), sent.getCaseId());
            return sent;
        } catch (FitConnectSenderException e) {
            log.warn("Failed to send submission (service={}) to destination {}",
                    submissionToSend.getServiceId(), destinationId, e);
            throw new SubmissionSendException("Failed to send submission to destination " + destinationId, e);
        }
    }

    private static UUID requireDestinationId(SubmissionToSend submissionToSend) {
        if (submissionToSend.getDestinationId() == null) {
            throw new IllegalStateException(
                    "No destination id: set SubmissionToSend.builder(...).destinationId(...) before sending");
        }
        return submissionToSend.getDestinationId();
    }

    private static SendableSubmission buildSubmission(UUID destinationId, SubmissionToSend submissionToSend) {
        ServiceTypeStep afterDestination = SendableSubmission.Builder().setDestination(destinationId);

        DataStep afterServiceType = submissionToSend.getServiceRegion() != null
                ? afterDestination.setServiceTypeWithRegion(submissionToSend.getServiceId(), submissionToSend.getServiceName(), submissionToSend.getServiceRegion())
                : afterDestination.setServiceType(submissionToSend.getServiceId(), submissionToSend.getServiceName());

        OptionalPropertiesStep step = submissionToSend.getDataFormat() == DataFormat.XML
                ? afterServiceType.setXmlData(submissionToSend.getData(), submissionToSend.getDataSchema())
                : afterServiceType.setJsonData(submissionToSend.getData(), submissionToSend.getDataSchema());

        for (AttachmentToSend attachment : submissionToSend.getAttachments()) {
            step = step.addAttachment(attachment.toAttachment());
        }
        if (submissionToSend.getReplyChannelEmail() != null) {
            step = step.setReplyChannel(ReplyChannel.ofEmail(submissionToSend.getReplyChannelEmail()));
        }
        if (submissionToSend.getCaseId() != null) {
            step = step.setCase(submissionToSend.getCaseId());
        }
        if (submissionToSend.getMetadataVersion() != null) {
            step = step.preferMetadataVersion(MetadataVersions.resolve(submissionToSend.getMetadataVersion()));
        }
        if (!submissionToSend.getDataSets().isEmpty()) {
            List<DataSet> dataSets = submissionToSend.getDataSets().stream()
                    .map(DataSetToSend::toDataSet)
                    .collect(Collectors.toList());
            step = step.setDataSets(dataSets);
        }

        return step.build();
    }
}
