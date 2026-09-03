package com.gfi.ozg.fitko.spring.it.support;

import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.tools.keygen.TestKeyBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mints throwaway RSA JWKs with the SDK's own {@link TestKeyBuilder} and
 * writes them to temp files, for the tests that need a syntactically valid
 * key that does <em>not</em> have to match any real destination:
 * {@code StarterConsumabilityIT} (context load only) and
 * {@code MultiDestinationRoundTripIT}'s deliberately-broken extra destination.
 */
public final class ThrowawayJwks {

    private ThrowawayJwks() {
    }

    public static String signingKeyResource() {
        return "file:" + write("signing", TestKeyBuilder.generateSignatureKeyPair().getPrivateKey());
    }

    public static String decryptionKeyResource() {
        return "file:" + write("decryption", TestKeyBuilder.generateEncryptionKeyPair().getPrivateKey());
    }

    private static Path write(String name, JWK privateKey) {
        try {
            Path file = Files.createTempFile("fitko-spring-it-throwaway-" + name + "-", ".json");
            file.toFile().deleteOnExit();
            Files.writeString(file, privateKey.toJSONString());
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
