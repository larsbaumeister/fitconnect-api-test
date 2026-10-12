package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.AttachmentSpec;
import com.gfi.ozg.fitko.swing.model.DataFormat;
import dev.fitko.fitconnect.api.config.defaults.MetadataVersion;
import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.api.domain.sender.SendableSubmission;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.DataStep;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.OptionalPropertiesStep;
import dev.fitko.fitconnect.api.domain.sender.steps.unencrypted.ServiceTypeStep;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link SubmissionDraft} to the SDK's {@link SendableSubmission}. Same
 * builder walk as {@code fitko-spring}'s {@code DefaultSubmissionSender}.
 */
public final class SubmissionFactory {

    private SubmissionFactory() {
    }

    public static SendableSubmission build(SubmissionDraft draft) {
        require(draft.destinationId() != null, "Destination (Zustellpunkt) is missing");
        require(!SdkConfig.isBlank(draft.serviceId()), "Service id is missing");
        require(!SdkConfig.isBlank(draft.serviceName()), "Service name is missing");
        require(draft.dataSchema() != null, "Data schema is missing");
        require(!SdkConfig.isBlank(draft.payload()), "Payload is empty");

        ServiceTypeStep afterDestination = SendableSubmission.Builder().setDestination(draft.destinationId());

        DataStep afterServiceType = SdkConfig.isBlank(draft.serviceRegion())
                ? afterDestination.setServiceType(draft.serviceId().trim(), draft.serviceName().trim())
                : afterDestination.setServiceTypeWithRegion(draft.serviceId().trim(), draft.serviceName().trim(), draft.serviceRegion().trim());

        OptionalPropertiesStep step = draft.dataFormat() == DataFormat.JSON
                ? afterServiceType.setJsonData(draft.payload(), draft.dataSchema())
                : afterServiceType.setXmlData(draft.payload(), draft.dataSchema());

        for (AttachmentSpec spec : draft.attachments()) {
            Path path = Path.of(spec.path);
            String fileName = path.getFileName().toString();
            step = step.addAttachment(Attachment.fromPath(path, spec.mimeType, fileName, fileName));
        }
        if (!SdkConfig.isBlank(draft.replyChannelEmail())) {
            step = step.setReplyChannel(ReplyChannel.ofEmail(draft.replyChannelEmail().trim()));
        }
        if (draft.caseId() != null) {
            step = step.setCase(draft.caseId());
        }
        if (!SdkConfig.isBlank(draft.metadataVersion())) {
            step = step.preferMetadataVersion(metadataVersion(draft.metadataVersion().trim()));
        }
        return step.build();
    }

    /** Metadata versions this SDK can write, e.g. {@code 1.4.0}, {@code 2.0.0}. */
    public static List<String> supportedMetadataVersions() {
        return Arrays.stream(MetadataVersion.values())
                .map(v -> v.getVersion().getVersionAsString())
                .collect(Collectors.toList());
    }

    static MetadataVersion metadataVersion(String raw) {
        return Arrays.stream(MetadataVersion.values())
                .filter(v -> v.getVersion().getVersionAsString().equals(raw))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown metadata version '" + raw
                        + "', supported by this SDK: " + String.join(", ", supportedMetadataVersions())));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
