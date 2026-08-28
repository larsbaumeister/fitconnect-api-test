package com.gfi.ozg.fitko.spring.support;

import com.nimbusds.jose.jwk.JWK;
import dev.fitko.fitconnect.api.domain.crypto.JWKPair;
import dev.fitko.fitconnect.tools.keygen.TestKeyBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mints throwaway RSA JWKs for tests using the SDK's own {@link
 * TestKeyBuilder}, so no key material has to be committed to the repository.
 */
public final class TestJwkKeys {

    private TestJwkKeys() {
    }

    public static Path writeSigningKey(Path directory) {
        return writeSigningKey(directory, "signing_key.json");
    }

    /** Overload for tests that need more than one distinct signing key in the same directory (e.g. one per destination). */
    public static Path writeSigningKey(Path directory, String fileName) {
        return writeKey(directory, fileName, TestKeyBuilder.generateSignatureKeyPair().getPrivateKey());
    }

    public static Path writeDecryptionKey(Path directory) {
        return writeDecryptionKey(directory, "decryption_key.json");
    }

    /** Overload for tests that need more than one distinct decryption key in the same directory (e.g. one per destination). */
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
