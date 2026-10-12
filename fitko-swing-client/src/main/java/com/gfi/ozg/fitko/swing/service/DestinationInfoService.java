package com.gfi.ozg.fitko.swing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.DestinationInfo;
import com.gfi.ozg.fitko.swing.store.Json;
import dev.fitko.fitconnect.api.config.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a Zustellpunkt's public data ({@code GET /v2/destinations/{id}} on the
 * submission API, the same call the SDK makes before every send) so the
 * compose form can offer its services, schemas and regions.
 *
 * <p>The SDK's {@code ClientFactory} doesn't hand out the service that wraps
 * this call, so it's done here with the JDK's HTTP client: an OAuth
 * client-credentials token from the connection's auth server, then the GET.
 * The URLs come from the SDK's own environment definitions via
 * {@link SdkConfig}.
 */
public class DestinationInfoService {

    private static final Logger log = LoggerFactory.getLogger(DestinationInfoService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Fetches an OAuth token - proves the credentials and the auth URL work. */
    public String fetchToken(Connection connection) throws IOException, InterruptedException {
        ApplicationConfig config = SdkConfig.create(connection);
        return fetchToken(config.getAuthBaseUrl(), connection.resolvedClientId(), connection.resolvedClientSecret());
    }

    public DestinationInfo fetch(Connection connection, UUID destinationId) throws IOException, InterruptedException {
        ApplicationConfig config = SdkConfig.create(connection);
        String token = fetchToken(config.getAuthBaseUrl(), connection.resolvedClientId(), connection.resolvedClientSecret());
        List<String> baseUrls = config.getCurrentEnvironment().getSubmissionBaseUrls();
        IOException last = null;
        // An environment may list several submission APIs (e.g. the FITKO one and Niedersachsen's);
        // a destination lives on exactly one of them.
        for (String baseUrl : baseUrls) {
            String url = stripTrailingSlash(baseUrl) + "/v2/destinations/" + destinationId;
            log.info("GET {}", url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parse(response.body());
            }
            last = new IOException("GET " + url + " returned HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            if (response.statusCode() != 404) {
                break;
            }
        }
        throw last != null ? last : new IOException("No submission base URL configured");
    }

    private String fetchToken(String authBaseUrl, String clientId, String clientSecret) throws IOException, InterruptedException {
        String url = stripTrailingSlash(authBaseUrl) + "/token";
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId.trim(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret.trim(), StandardCharsets.UTF_8);
        log.info("POST {} (client {})", url, clientId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Token request to " + url + " failed with HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        JsonNode json = Json.MAPPER.readTree(response.body());
        String token = json.path("access_token").asText(null);
        if (token == null) {
            throw new IOException("Token response from " + url + " has no access_token");
        }
        return token;
    }

    /** Parses the destination JSON leniently - unknown fields are ignored, missing ones stay empty. */
    public static DestinationInfo parse(String body) throws IOException {
        JsonNode json = Json.MAPPER.readTree(body);
        DestinationInfo info = new DestinationInfo();
        info.status = json.path("status").asText(null);
        json.path("metadataVersions").forEach(v -> info.metadataVersions.add(v.asText()));

        Set<String> replyChannels = new LinkedHashSet<>(fieldNames(json.path("replyChannels")));
        for (JsonNode serviceNode : json.path("publicServices")) {
            DestinationInfo.ServiceInfo service = new DestinationInfo.ServiceInfo();
            service.identifier = serviceNode.path("identifier").asText(null);
            serviceNode.path("regions").forEach(r -> service.regions.add(r.asText()));
            for (JsonNode schemaNode : serviceNode.path("submissionSchemas")) {
                DestinationInfo.SchemaInfo schema = new DestinationInfo.SchemaInfo();
                schema.schemaUri = schemaNode.path("schemaUri").asText(null);
                schema.mimeType = schemaNode.path("mimeType").asText(null);
                service.schemas.add(schema);
            }
            info.services.add(service);
        }
        info.replyChannels = new ArrayList<>(replyChannels);
        info.fetchedAt = Instant.now();
        return info;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!node.get(name).isNull()) {
                names.add(name);
            }
        }
        return names;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
