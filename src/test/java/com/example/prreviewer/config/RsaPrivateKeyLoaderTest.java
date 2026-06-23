package com.example.prreviewer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaPrivateKeyLoaderTest {

    @Test
    void loadsPkcs1RsaPrivateKey(@TempDir Path tmp) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        // JcaPEMWriter emits a traditional OpenSSL "RSA PRIVATE KEY" (PKCS#1)
        // block — exactly the format GitHub hands out.
        String pem;
        try (StringWriter sw = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(keyPair.getPrivate());
            writer.flush();
            pem = sw.toString();
        }
        assertTrue(pem.contains("BEGIN RSA PRIVATE KEY"), "expected a PKCS#1 PEM");

        Path keyFile = tmp.resolve("github-app.pem");
        Files.writeString(keyFile, pem);

        PrivateKey loaded = RsaPrivateKeyLoader.loadFromPem(keyFile);

        RSAPrivateKey original = (RSAPrivateKey) keyPair.getPrivate();
        RSAPrivateKey result = (RSAPrivateKey) loaded;
        assertEquals("RSA", loaded.getAlgorithm());
        assertEquals(original.getModulus(), result.getModulus());
        assertEquals(original.getPrivateExponent(), result.getPrivateExponent());
    }
}
