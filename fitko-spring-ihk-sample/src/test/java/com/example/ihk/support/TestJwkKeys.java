package com.example.ihk.support;

import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.tools.keygen.TestKeyBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mints throwaway RSA JWKs for tests using the SDK's own {@link
 * TestKeyBuilder}, so no key material has to be committed to the repository.
 * Mirrors {@code fitko-spring}'s own test-only helper of the same name (not
 * reusable directly - it lives in {@code fitko-spring}'s {@code src/test},
 * not published to consumers).
 */
public final class TestJwkKeys {

    private TestJwkKeys() {
    }

    public static Path writeSigningKey(Path directory, String fileName) {
        return writeKey(directory, fileName, TestKeyBuilder.generateSignatureKeyPair().getPrivateKey());
    }

    public static Path writeDecryptionKey(Path directory, String fileName) {
        return writeKey(directory, fileName, TestKeyBuilder.generateEncryptionKeyPair().getPrivateKey());
    }

    private static Path writeKey(Path directory, String fileName, JWK privateKey) {
        Path file = directory.resolve(fileName);
        try {
            Files.writeString(file, privateKey.toJSONString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
