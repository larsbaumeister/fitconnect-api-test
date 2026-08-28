package com.gfi.ozg.fitko.receiver;

import dev.fitko.fitconnect.api.domain.model.attachment.Attachment;
import dev.fitko.fitconnect.api.domain.model.metadata.AdditionalReferenceInfo;
import dev.fitko.fitconnect.api.domain.model.metadata.AuthenticationInformation;
import dev.fitko.fitconnect.api.domain.model.metadata.Metadata;
import dev.fitko.fitconnect.api.domain.model.metadata.v1.MetadataV1;
import dev.fitko.fitconnect.api.domain.model.metadata.v2.DataSet;
import dev.fitko.fitconnect.api.domain.model.metadata.v2.MetadataV2;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.Elster;
import dev.fitko.fitconnect.api.domain.model.reply.replychannel.ReplyChannel;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Persists a {@link ReceivedSubmission} (data, metadata, and attachments) to
 * a directory on disk, named after the submission id. Files written here are
 * meant to be picked up and moved into the actual Fachverfahren; see the
 * Java-SDK docs' note on treating the SDK's own attachment storage as
 * temporary.
 */
final class ReceivedSubmissionWriter {

    private final SubmissionSnapshotWriter snapshotWriter = new SubmissionSnapshotWriter();

    Path write(ReceivedSubmission submission, Path outputDir) {
        try {
            Path submissionDir = outputDir.resolve(submission.getSubmissionId().toString());
            Files.createDirectories(submissionDir);

            Files.writeString(submissionDir.resolve("data"), submission.getDataAsString(), StandardCharsets.UTF_8);
            Files.writeString(submissionDir.resolve("metadata.properties"), buildMetadata(submission), StandardCharsets.UTF_8);
            writeAttachments(submission, submissionDir);
            snapshotWriter.write(submission, submissionDir);

            return submissionDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist submission " + submission.getSubmissionId(), e);
        }
    }

    private static String buildMetadata(ReceivedSubmission submission) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("submissionId=").append(submission.getSubmissionId()).append('\n');
        metadata.append("caseId=").append(submission.getCaseId()).append('\n');
        metadata.append("destinationId=").append(submission.getDestinationId()).append('\n');
        metadata.append("submittedAt=").append(submission.getSubmittedAt()).append('\n');
        metadata.append("dataMimeType=").append(submission.getDataMimeType()).append('\n');
        metadata.append("dataSchemaUri=").append(submission.getDataSchemaUri()).append('\n');
        metadata.append("serviceIdentifier=").append(submission.getServiceType().getIdentifier()).append('\n');
        metadata.append("serviceName=").append(submission.getServiceType().getName()).append('\n');
        appendUserReference(metadata, submission);
        return metadata.toString();
    }

    /**
     * Surfaces the applicant/user references FIT-Connect can actually carry:
     * the reply channel (which, for ELSTER or a BundID/DeutschlandID Postfach,
     * doubles as the identifier to use for follow-up correlation and as the
     * address to send information back to), the BundID Statusmonitor
     * application id, and any generic {@code dataSet}/{@code
     * authenticationInformation} entries the sender chose to attach (e.g. a
     * BundID/ELSTER authentication/trust-level proof - FIT-Connect has no
     * dedicated field for this, so it only appears here if the sending side
     * put it in one of these generic slots). Everything else about the
     * applicant lives in the service-specific Fachdaten (see {@code data}),
     * not in FIT-Connect's own metadata.
     */
    private static void appendUserReference(StringBuilder metadata, ReceivedSubmission submission) {
        Metadata submissionMetadata = submission.getMetadata();

        ReplyChannel replyChannel = submissionMetadata.getReplyChannel();
        if (replyChannel != null) {
            if (replyChannel.isElster()) {
                Elster elster = replyChannel.getElster();
                metadata.append("replyChannelType=elster\n");
                metadata.append("elsterAccountId=").append(elster.getAccountId()).append('\n');
                if (elster.getDeliveryTicket() != null) {
                    metadata.append("elsterDeliveryTicket=").append(elster.getDeliveryTicket()).append('\n');
                }
                if (elster.getReference() != null) {
                    metadata.append("elsterReference=").append(elster.getReference()).append('\n');
                }
            } else if (replyChannel.isIdBundDeMailbox()) {
                metadata.append("replyChannelType=idBundDeMailbox\n");
                metadata.append("bundIdMailboxUuid=").append(replyChannel.getIdBundDeMailbox().getMailboxUuid()).append('\n');
            } else if (replyChannel.isEMail()) {
                metadata.append("replyChannelType=eMail\n");
                metadata.append("replyChannelEmail=").append(replyChannel.getEmail().getAddress()).append('\n');
            } else if (replyChannel.isDeMail()) {
                metadata.append("replyChannelType=deMail\n");
            } else if (replyChannel.isFitConnect()) {
                metadata.append("replyChannelType=fitConnect\n");
            } else if (replyChannel.isFink()) {
                metadata.append("replyChannelType=fink\n");
            }
        }

        AdditionalReferenceInfo additionalReferenceInfo = submissionMetadata.getAdditionalReferenceInfo();
        if (additionalReferenceInfo != null && additionalReferenceInfo.getIdBundDeApplicationId() != null) {
            metadata.append("idBundDeApplicationId=").append(additionalReferenceInfo.getIdBundDeApplicationId()).append('\n');
        }

        if (submissionMetadata instanceof MetadataV1) {
            appendAuthenticationInformation(metadata, (MetadataV1) submissionMetadata);
        } else if (submissionMetadata instanceof MetadataV2) {
            appendDataSets(metadata, (MetadataV2) submissionMetadata);
        }
    }

    private static void appendAuthenticationInformation(StringBuilder metadata, MetadataV1 metadataV1) {
        List<AuthenticationInformation> authenticationInformation = metadataV1.getAuthenticationInformation();
        if (authenticationInformation == null) {
            return;
        }
        for (int i = 0; i < authenticationInformation.size(); i++) {
            AuthenticationInformation info = authenticationInformation.get(i);
            metadata.append("authenticationInformation[").append(i).append("].type=").append(info.getType()).append('\n');
            metadata.append("authenticationInformation[").append(i).append("].version=").append(info.getVersion()).append('\n');
            metadata.append("authenticationInformation[").append(i).append("].content=").append(info.getContent()).append('\n');
        }
    }

    private static void appendDataSets(StringBuilder metadata, MetadataV2 metadataV2) {
        List<DataSet> dataSets = metadataV2.getDataSets();
        if (dataSets == null) {
            return;
        }
        for (int i = 0; i < dataSets.size(); i++) {
            DataSet dataSet = dataSets.get(i);
            if (dataSet.getSchema() != null) {
                metadata.append("dataSet[").append(i).append("].schemaUri=").append(dataSet.getSchema().getSchemaUri()).append('\n');
                metadata.append("dataSet[").append(i).append("].mimeType=").append(dataSet.getSchema().getMimeType()).append('\n');
            }
            if (dataSet.getDescription() != null) {
                metadata.append("dataSet[").append(i).append("].description=").append(dataSet.getDescription()).append('\n');
            }
            metadata.append("dataSet[").append(i).append("].content=").append(dataSet.getContent()).append('\n');
        }
    }

    private static void writeAttachments(ReceivedSubmission submission, Path submissionDir) throws IOException {
        if (submission.getAttachments().isEmpty()) {
            return;
        }
        Path attachmentsDir = submissionDir.resolve("attachments");
        Files.createDirectories(attachmentsDir);
        for (Attachment attachment : submission.getAttachments()) {
            Path target = attachmentsDir.resolve(safeFileName(attachment));
            if (attachment.isLargeAttachment()) {
                try (InputStream in = attachment.getDataAsInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.write(target, attachment.getDataAsBytes());
            }
        }
    }

    private static String safeFileName(Attachment attachment) {
        String name = attachment.getFileName();
        return (name == null || name.isBlank()) ? attachment.getAttachmentId() + ".bin" : name;
    }
}
