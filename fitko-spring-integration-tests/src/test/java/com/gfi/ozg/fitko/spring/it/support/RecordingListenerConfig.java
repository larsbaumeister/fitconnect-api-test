package com.gfi.ozg.fitko.spring.it.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers a single {@link RecordingListener} bean. A test that uses the
 * default single-listener setup adds {@code @Import(RecordingListenerConfig.class)};
 * tests that need several differently-filtered listeners declare their own
 * nested {@code @TestConfiguration} instead.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingListenerConfig {

    @Bean
    RecordingListener recordingListener() {
        return new RecordingListener();
    }
}
