package dev.fitko.fitconnect.samples.sender;

import dev.fitko.fitconnect.api.config.ApplicationConfig;
import dev.fitko.fitconnect.api.domain.model.event.Status;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import dev.fitko.fitconnect.client.SenderClient;
import dev.fitko.fitconnect.client.bootstrap.ApplicationConfigLoader;
import dev.fitko.fitconnect.client.bootstrap.ClientFactory;
import dev.fitko.fitconnect.samples.common.cli.CliUsageException;

/**
 * Sample "Onlinedienst" (sending) client for FIT-Connect.
 *
 * <p>Builds one submission from CLI-supplied data/attachments and sends it to
 * a FIT-Connect destination via the FIT-Connect Java SDK. All secrets,
 * credentials, and endpoints are passed in as command line arguments; none
 * are read from a config file. See {@link #USAGE} for the full option list,
 * or run with {@code --help}.
 */
public final class SenderApp {

    private static final String USAGE = ""
            + "Usage: fitconnect-sender-sample [options]\n"
            + "\n"
            + "Required:\n"
            + "  --client-id <id>              Sender client id from the Self-Service-Portal\n"
            + "  --client-secret <secret>      Sender client secret from the Self-Service-Portal\n"
            + "  --destination-id <uuid>       Destination (Zustellpunkt) to send to\n"
            + "  --service-id <urn>            Service identifier, e.g. a LeiKa key URN\n"
            + "  --service-name <name>         Human readable service name\n"
            + "  --data-schema <uri>           Schema URI/URN describing --data(-file)\n"
            + "  --data <json>                 Submission data as an inline JSON string\n"
            + "                                  (mutually exclusive with --data-file)\n"
            + "  --data-file <path>            Submission data read from a file\n"
            + "                                  (mutually exclusive with --data)\n"
            + "\n"
            + "Optional:\n"
            + "  --data-format <json|xml>      Format of --data(-file) (default: json)\n"
            + "  --environment <name>          TEST (default), STAGE, PROD, or a custom name\n"
            + "  --service-region <region>     Region qualifier for the service identifier\n"
            + "  --case-id <uuid>              Append this submission to an existing case\n"
            + "  --attachment <path;mime[;name]>\n"
            + "                                Attach a file; repeatable\n"
            + "  --reply-channel-email <email> Ask the receiver to reply to this address\n"
            + "  --reply-channel-elster-account-id <id>\n"
            + "                                Ask the receiver to reply via ELSTER to this account\n"
            + "  --reply-channel-elster-delivery-ticket <ticket>\n"
            + "                                Optional ELSTER delivery ticket (needs account-id)\n"
            + "  --reply-channel-elster-reference <geschaeftszeichen>\n"
            + "                                Optional ELSTER reference (needs account-id)\n"
            + "  --reply-channel-bundid-mailbox <uuid>\n"
            + "                                Ask the receiver to reply to this BundID/DeutschlandID\n"
            + "                                  Postfach (the citizen's Postkorb-Handle)\n"
            + "                                  (--reply-channel-* flags are mutually exclusive)\n"
            + "  --id-bund-de-application-id <uuid>\n"
            + "                                BundID/DeutschlandID application id for the Statusmonitor\n"
            + "  --metadata-version <x.y.z>    Force a metadata schema version (e.g. 2.1.0)\n"
            + "                                  instead of auto-negotiating with the destination\n"
            + "  --data-set <schemaUri;mimeType;content>\n"
            + "                                Attach a generic dataSet (metadata v2.x+ only);\n"
            + "                                  repeatable. FIT-Connect's slot for information it has\n"
            + "                                  no dedicated field for, e.g. a BundID/ELSTER\n"
            + "                                  authentication/trust-level proof, using a schema you\n"
            + "                                  and the receiver agree on out-of-band\n"
            + "  --data-set-file <schemaUri;mimeType;path>\n"
            + "                                Same as --data-set, with content read from a file;\n"
            + "                                  repeatable\n"
            + "  --auth-base-url <url>         Override the OAuth token endpoint\n"
            + "  --routing-base-url <url>      Override the Routing API endpoint\n"
            + "  --submission-base-url <url>   Override a Submission API endpoint; repeatable\n"
            + "  --self-service-portal-base-url <url>\n"
            + "                                Override the Self-Service-Portal endpoint\n"
            + "  --destination-base-url <url>  Override the Destination API endpoint\n"
            + "  --allow-insecure-public-key   Accept self-signed destination certificates\n"
            + "  --skip-submission-data-validation\n"
            + "                                Skip local schema validation of --data(-file)\n"
            + "  --disable-auto-reject         Do not auto-reject invalid replies\n"
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
            SenderOptions options = SenderOptionsParser.parse(args);

            String yaml = SenderYamlConfigFactory.toYaml(options);
            ApplicationConfig config = ApplicationConfigLoader.loadConfigFromYamlString(yaml);
            SenderClient senderClient = ClientFactory.createSenderClient(config);

            SentSubmission sentSubmission = new SubmissionSubmitter(senderClient).submit(options);

            System.out.println("Submission sent successfully.");
            System.out.println("  submissionId : " + sentSubmission.getSubmissionId());
            System.out.println("  caseId       : " + sentSubmission.getCaseId());
            System.out.println("  destinationId: " + sentSubmission.getDestinationId());

            Status status = senderClient.getSubmissionStatus(sentSubmission);
            System.out.println("  status       : " + status.getState().getName());

        } catch (CliUsageException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        } catch (RuntimeException e) {
            System.err.println("Sending submission failed: " + e.getMessage());
            System.exit(1);
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

    private SenderApp() {
    }
}
