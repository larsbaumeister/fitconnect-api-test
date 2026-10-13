package com.gfi.ozg.fitko.swing.service;

import com.gfi.ozg.fitko.swing.model.Connection;
import dev.fitko.fitconnect.rest.client.config.FitConnectEnvironment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SdkConfigTest {

    @Test
    void builtInEnvironmentWithoutOverridesIsTheSdkDefault() {
        assertEquals(FitConnectEnvironment.TEST, SdkConfig.environment(new Connection()));
    }

    @Test
    void overridesReplaceOnlyTheirUrl() {
        Connection c = new Connection();
        c.environment = "STAGE";
        c.authBaseUrl = "https://auth.example.org/";
        FitConnectEnvironment env = SdkConfig.environment(c);
        assertEquals("https://auth.example.org/token", env.authUrl());
        assertEquals(FitConnectEnvironment.STAGE.submissionApiUrl(), env.submissionApiUrl());
        assertEquals(FitConnectEnvironment.STAGE.routingUrl(), env.routingUrl());
    }

    @Test
    void authUrlWithTokenPathIsKeptAsIs() {
        assertEquals("https://auth.example.org/token", SdkConfig.tokenUrl("https://auth.example.org/token"));
    }

    @Test
    void customEnvironmentNeedsBothUrls() {
        Connection c = new Connection();
        c.environment = "LOCAL";
        c.authBaseUrl = "http://localhost:8080";
        assertThrows(IllegalStateException.class, () -> SdkConfig.environment(c));

        c.submissionBaseUrl = "http://localhost:8081/submission-api/";
        FitConnectEnvironment env = SdkConfig.environment(c);
        assertEquals("LOCAL", env.name());
        assertEquals("http://localhost:8081/submission-api", env.submissionApiUrl());
    }
}
