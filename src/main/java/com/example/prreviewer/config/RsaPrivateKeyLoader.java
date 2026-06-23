package com.example.prreviewer.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Loads an RSA private key from a PEM file.
 * <p>
 * GitHub hands out App keys in <strong>PKCS#1</strong> form
 * ({@code -----BEGIN RSA PRIVATE KEY-----}), which the JDK's built-in
 * {@code KeyFactory}/{@code PKCS8EncodedKeySpec} cannot read directly. Rather
 * than forcing the user to run {@code openssl} to convert to PKCS#8, we use
 * BouncyCastle's {@link PEMParser}, which understands both PKCS#1 and PKCS#8.
 */
public final class RsaPrivateKeyLoader {

    private RsaPrivateKeyLoader() {
    }

    public static PrivateKey loadFromPem(Path pemPath) {
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(pemPath, StandardCharsets.UTF_8))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

            if (parsed instanceof PEMKeyPair pemKeyPair) {
                // PKCS#1: "BEGIN RSA PRIVATE KEY"
                return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
            }
            if (parsed instanceof PrivateKeyInfo privateKeyInfo) {
                // PKCS#8: "BEGIN PRIVATE KEY"
                return converter.getPrivateKey(privateKeyInfo);
            }
            throw new IllegalArgumentException(
                    "Unsupported private key PEM at " + pemPath + ": "
                            + (parsed == null ? "empty/unreadable" : parsed.getClass().getName()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read private key from " + pemPath, e);
        }
    }
}
