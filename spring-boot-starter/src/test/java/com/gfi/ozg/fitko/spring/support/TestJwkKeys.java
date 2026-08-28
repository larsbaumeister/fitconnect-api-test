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
        return writeKey(directory, "signing_key.json", TestKeyBuilder.generateSignatureKeyPair().getPrivateKey());
    }

    public static Path writeDecryptionKey(Path directory) {
        return writeKey(directory, "decryption_key.json", TestKeyBuilder.generateEncryptionKeyPair().getPrivateKey());
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
