package com.gfi.ozg.fitko.swing.model;

import java.util.UUID;

/**
 * A named set of sender-client credentials for one FIT-Connect environment.
 * The secret is either stored here or read from environment variables at
 * use time, so it never has to touch the disk.
 */
public class Connection {

    public static final String DEFAULT_CLIENT_ID_ENV = "FITCONNECT_SENDER_CLIENT_ID";
    public static final String DEFAULT_CLIENT_SECRET_ENV = "FITCONNECT_SENDER_CLIENT_SECRET";

    public String id = UUID.randomUUID().toString();
    public String label = "";
    /** TEST, STAGE, PROD or a custom name (which then needs the base URLs below). */
    public String environment = "TEST";
    public String clientId;
    public String clientSecret;
    public boolean credentialsFromEnv;
    public String clientIdEnvVar = DEFAULT_CLIENT_ID_ENV;
    public String clientSecretEnvVar = DEFAULT_CLIENT_SECRET_ENV;
    /** Optional overrides of the SDK's built-in environment URLs. */
    public String authBaseUrl;
    public String submissionBaseUrl;

    public String resolvedClientId() {
        return credentialsFromEnv ? System.getenv(clientIdEnvVar) : clientId;
    }

    public String resolvedClientSecret() {
        return credentialsFromEnv ? System.getenv(clientSecretEnvVar) : clientSecret;
    }

    public boolean isTestEnvironment() {
        return "TEST".equalsIgnoreCase(environment);
    }

    public Connection copy() {
        Connection c = new Connection();
        c.id = id;
        c.label = label;
        c.environment = environment;
        c.clientId = clientId;
        c.clientSecret = clientSecret;
        c.credentialsFromEnv = credentialsFromEnv;
        c.clientIdEnvVar = clientIdEnvVar;
        c.clientSecretEnvVar = clientSecretEnvVar;
        c.authBaseUrl = authBaseUrl;
        c.submissionBaseUrl = submissionBaseUrl;
        return c;
    }

    @Override
    public String toString() {
        return label + " (" + environment + ")";
    }
}
