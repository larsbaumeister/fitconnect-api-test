package dev.fitko.fitconnect.samples.sender;

import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.api.domain.sender.SendableSubmission;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.DataStep;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.OptionalPropertiesStep;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.ServiceTypeStep;
import dev.fitko.fitconnect.client.SenderClient;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a {@link SendableSubmission} from {@link SenderOptions} and hands it
 * to the {@link SenderClient}. Kept separate from {@link SenderApp} so the
 * "how to assemble a submission" logic can be read (and reused) on its own.
 */
final class SubmissionSubmitter {

    private final SenderClient senderClient;

    SubmissionSubmitter(SenderClient senderClient) {
        this.senderClient = senderClient;
    }

    SentSubmission submit(SenderOptions options) {
        ServiceTypeStep afterDestination = SendableSubmission.Builder().setDestination(options.getDestinationId());

        DataStep afterServiceType = options.getServiceRegion() != null
                ? afterDestination.setServiceTypeWithRegion(options.getServiceId(), options.getServiceName(), options.getServiceRegion())
                : afterDestination.setServiceType(options.getServiceId(), options.getServiceName());

        OptionalPropertiesStep step = options.getDataFormat() == DataFormat.XML
                ? afterServiceType.setXmlData(options.getData(), options.getDataSchema())
                : afterServiceType.setJsonData(options.getData(), options.getDataSchema());

        for (AttachmentSpec attachment : options.getAttachments()) {
            step = step.addAttachment(attachment.toAttachment());
        }
        if (options.getReplyChannel() != null) {
            step = step.setReplyChannel(options.getReplyChannel().toReplyChannel());
        }
        if (options.getIdBundDeApplicationId() != null) {
            step = step.setIdBundDeApplicationId(options.getIdBundDeApplicationId());
        }
        if (options.getCaseId() != null) {
            step = step.setCase(options.getCaseId());
        }
        if (options.getMetadataVersion() != null) {
            step = step.preferMetadataVersion(MetadataVersionFactory.create(options.getMetadataVersion()));
        }
        if (!options.getDataSets().isEmpty()) {
            List<DataSet> dataSets = options.getDataSets().stream()
                    .map(DataSetSpec::toDataSet)
                    .collect(Collectors.toList());
            step = step.setDataSets(dataSets);
        }

        SendableSubmission submission = step.build();
        return senderClient.send(submission);
    }
}
