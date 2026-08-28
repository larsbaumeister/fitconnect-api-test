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
 * Builds a {@link SendableSubmission} from an {@link AntragToSend} and hands
 * it to the SDK's {@link SenderClient}. Mirrors the CLI sender sample's
 * {@code SubmissionSubmitter}.
 */
@Slf4j
public class DefaultAntragSender implements AntragSender {

    private final SenderClient senderClient;

    public DefaultAntragSender(SenderClient senderClient) {
        this.senderClient = senderClient;
    }

    @Override
    public SentSubmission send(AntragToSend antrag) {
        UUID destinationId = requireDestinationId(antrag);
        SendableSubmission submission = buildSubmission(destinationId, antrag);
        log.debug("Sending Antrag (service={}) to destination {}", antrag.getServiceId(), destinationId);
        try {
            SentSubmission sent = senderClient.send(submission);
            log.debug("Sent Antrag to destination {}: submissionId={}, caseId={}",
                    destinationId, sent.getSubmissionId(), sent.getCaseId());
            return sent;
        } catch (FitConnectSenderException e) {
            log.warn("Failed to send Antrag (service={}) to destination {}",
                    antrag.getServiceId(), destinationId, e);
            throw new AntragSendException("Failed to send Antrag to destination " + destinationId, e);
        }
    }

    private static UUID requireDestinationId(AntragToSend antrag) {
        if (antrag.getDestinationId() == null) {
            throw new IllegalStateException(
                    "No destination id: set AntragToSend.builder(...).destinationId(...) before sending");
        }
        return antrag.getDestinationId();
    }

    private static SendableSubmission buildSubmission(UUID destinationId, AntragToSend antrag) {
        ServiceTypeStep afterDestination = SendableSubmission.Builder().setDestination(destinationId);

        DataStep afterServiceType = antrag.getServiceRegion() != null
                ? afterDestination.setServiceTypeWithRegion(antrag.getServiceId(), antrag.getServiceName(), antrag.getServiceRegion())
                : afterDestination.setServiceType(antrag.getServiceId(), antrag.getServiceName());

        OptionalPropertiesStep step = antrag.getDataFormat() == DataFormat.XML
                ? afterServiceType.setXmlData(antrag.getData(), antrag.getDataSchema())
                : afterServiceType.setJsonData(antrag.getData(), antrag.getDataSchema());

        for (AttachmentToSend attachment : antrag.getAttachments()) {
            step = step.addAttachment(attachment.toAttachment());
        }
        if (antrag.getReplyChannelEmail() != null) {
            step = step.setReplyChannel(ReplyChannel.ofEmail(antrag.getReplyChannelEmail()));
        }
        if (antrag.getCaseId() != null) {
            step = step.setCase(antrag.getCaseId());
        }
        if (antrag.getMetadataVersion() != null) {
            step = step.preferMetadataVersion(MetadataVersions.resolve(antrag.getMetadataVersion()));
        }
        if (!antrag.getDataSets().isEmpty()) {
            List<DataSet> dataSets = antrag.getDataSets().stream()
                    .map(DataSetToSend::toDataSet)
                    .collect(Collectors.toList());
            step = step.setDataSets(dataSets);
        }

        return step.build();
    }
}
