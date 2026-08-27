package dev.fitko.fitconnect.samples.receiver;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;
import dev.fitko.fitconnect.api.domain.model.submission.SubmissionForPickup;
import dev.fitko.fitconnect.api.domain.subscriber.ReceivedSubmission;
import dev.fitko.fitconnect.client.SubscriberClient;
import dev.fitko.fitconnect.client.bootstrap.ApplicationConfigLoader;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import dev.fitko.fitconnect.samples.common.cli.CliUsageException;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sample "Verwaltungssystem" (receiving) client for FIT-Connect.
 *
 * <p>Polls a destination for available submissions (or fetches one specific
 * submission id), decrypts and persists each one to disk, and then
 * optionally accepts or rejects it via the FIT-Connect Java SDK. All secrets,
 * credentials, and keys are passed in as command line arguments; none are
 * read from a config file. See {@link #USAGE} for the full option list, or
 * run with {@code --help}.
 *
 * <p>By default, submissions are only downloaded, never accepted or
 * rejected, so a submission stays available for pickup until you explicitly
 * pass {@code --accept} or {@code --reject} (both are destructive: the
 * submission is deleted from the delivery service afterwards).
 */
public final class ReceiverApp {

    private static final String USAGE = ""
            + "Usage: fitconnect-receiver-sample [options]\n"
            + "\n"
            + "Required:\n"
            + "  --client-id <id>              Subscriber client id from the Self-Service-Portal\n"
            + "  --client-secret <secret>      Subscriber client secret from the Self-Service-Portal\n"
            + "  --destination-id <uuid>       Destination (Zustellpunkt) to poll\n"
            + "  --signing-key <path>          Path to the private signing key JWK\n"
            + "  --decryption-key <path>       Path to a private decryption key JWK; repeatable\n"
            + "                                  (more than one supports key rollover)\n"
            + "\n"
            + "Optional:\n"
            + "  --environment <name>          TEST (default), STAGE, PROD, or a custom name\n"
            + "  --submission-id <uuid>        Fetch only this submission instead of listing\n"
            + "  --offset <n>                  Paging offset when listing (default: 0)\n"
            + "  --limit <n>                   Paging limit when listing (default: 100)\n"
            + "  --output-dir <path>           Where to write downloaded submissions\n"
            + "                                  (default: ./fitconnect-received)\n"
            + "  --accept                      Accept every downloaded submission (deletes it\n"
            + "                                  from the delivery service afterwards)\n"
            + "  --reject                      Reject every downloaded submission instead\n"
            + "                                  (mutually exclusive with --accept)\n"
            + "  --reject-problem <name>       TechnicalError (default) or DataSchemaViolation\n"
            + "  --auth-base-url <url>         Override the OAuth token endpoint\n"
            + "  --routing-base-url <url>      Override the Routing API endpoint\n"
            + "  --submission-base-url <url>   Override a Submission API endpoint; repeatable\n"
            + "  --self-service-portal-base-url <url>\n"
            + "                                Override the Self-Service-Portal endpoint\n"
            + "  --destination-base-url <url>  Override the Destination API endpoint\n"
            + "  --allow-insecure-public-key   Accept self-signed destination certificates\n"
            + "  --skip-submission-data-validation\n"
            + "                                Skip local schema validation of received data\n"
            + "  --disable-auto-reject         Do not auto-reject invalid submissions\n"
            + "  --local-schema <uri=path>     Validate against a local schema file; repeatable\n"
            + "  --connect-timeout <seconds>   HTTP connect timeout (default: 30)\n"
            + "  --read-timeout <seconds>      HTTP read timeout (default: 30)\n"
            + "  --write-timeout <seconds>     HTTP write timeout (default: 30)\n"
            + "  --help                        Show this help text\n";

    public static void main(String[] args) {
        if (containsHelp(args)) {
            System.out.println(USAGE);
            return;
        }
        if (args.length == 0) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        try {
            ReceiverOptions options = ReceiverOptionsParser.parse(args);

            String yaml = ReceiverYamlConfigFactory.toYaml(options);
            ApplicationConfig config = ApplicationConfigLoader.loadConfigFromYamlString(yaml);
            SubscriberClient subscriberClient = ClientFactory.createSubscriberClient(config);

            SubmissionPickupService pickupService = new SubmissionPickupService(subscriberClient);
            ReceivedSubmissionWriter writer = new ReceivedSubmissionWriter();

            List<UUID> submissionIds = resolveSubmissionIds(options, pickupService);
            if (submissionIds.isEmpty()) {
                System.out.println("No submissions available for pickup.");
                return;
            }

            for (UUID submissionId : submissionIds) {
                processOne(submissionId, pickupService, writer, options);
            }

        } catch (CliUsageException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        } catch (RuntimeException e) {
            System.err.println("Receiving submissions failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<UUID> resolveSubmissionIds(ReceiverOptions options, SubmissionPickupService pickupService) {
        if (options.getSubmissionId() != null) {
            return List.of(options.getSubmissionId());
        }
        List<SubmissionForPickup> available =
                pickupService.listAvailable(options.getDestinationId(), options.getOffset(), options.getLimit());
        return available.stream().map(SubmissionForPickup::getSubmissionId).collect(Collectors.toList());
    }

    private static void processOne(UUID submissionId, SubmissionPickupService pickupService,
                                    ReceivedSubmissionWriter writer, ReceiverOptions options) {
        ReceivedSubmission submission = pickupService.fetch(submissionId);
        Path savedTo = writer.write(submission, options.getOutputDir());

        System.out.println("Submission received.");
        System.out.println("  submissionId : " + submission.getSubmissionId());
        System.out.println("  caseId       : " + submission.getCaseId());
        System.out.println("  destinationId: " + submission.getDestinationId());
        System.out.println("  savedTo      : " + savedTo);

        if (options.isAccept()) {
            submission.acceptSubmission();
            System.out.println("  outcome      : accepted (deleted from delivery service)");
        } else if (options.isReject()) {
            List<Problem> problems = ProblemFactory.create(options.getRejectProblem());
            submission.rejectSubmission(problems);
            System.out.println("  outcome      : rejected as " + options.getRejectProblem()
                    + " (deleted from delivery service)");
        } else {
            System.out.println("  outcome      : left on delivery service (pass --accept or --reject to change this)");
        }
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                return true;
            }
        }
        return false;
    }

    private ReceiverApp() {
    }
}
