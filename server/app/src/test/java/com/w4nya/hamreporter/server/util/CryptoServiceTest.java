package com.w4nya.hamreporter.server.util;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;

import org.junit.jupiter.api.Test;

public class CryptoServiceTest {

    @Test
    void generateKeyPairReturnsEd25519() {
        KeyPair kp = CryptoService.generateKeyPair();
        assertNotNull(kp);
        assertNotNull(kp.getPrivate());
        assertNotNull(kp.getPublic());
    }

    @Test
    void signAndVerifyRoundTrip() {
        KeyPair kp = CryptoService.generateKeyPair();
        String data = "hello world";
        String sig = CryptoService.signBase64(kp.getPrivate(), data);
        assertNotNull(sig);
        assertTrue(CryptoService.verifyBase64(kp.getPublic(), data, sig));
    }

    @Test
    void verifyFailsWithWrongData() {
        KeyPair kp = CryptoService.generateKeyPair();
        String sig = CryptoService.signBase64(kp.getPrivate(), "original");
        assertFalse(CryptoService.verifyBase64(kp.getPublic(), "tampered", sig));
    }

    @Test
    void verifyFailsWithWrongKey() {
        KeyPair kp1 = CryptoService.generateKeyPair();
        KeyPair kp2 = CryptoService.generateKeyPair();
        String sig = CryptoService.signBase64(kp1.getPrivate(), "data");
        assertFalse(CryptoService.verifyBase64(kp2.getPublic(), "data", sig));
    }

    @Test
    void verifyFailsWithNullKey() {
        assertFalse(CryptoService.verifyBase64(null, "data", "sig"));
    }

    @Test
    void verifyFailsWithNullSignature() {
        KeyPair kp = CryptoService.generateKeyPair();
        assertFalse(CryptoService.verifyBase64(kp.getPublic(), "data", null));
    }

    @Test
    void sha256HexProducesConsistentHash() {
        String h1 = CryptoService.sha256Hex("test");
        String h2 = CryptoService.sha256Hex("test");
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void sha256HexDiffersForDifferentInput() {
        String h1 = CryptoService.sha256Hex("foo");
        String h2 = CryptoService.sha256Hex("bar");
        assertNotEquals(h1, h2);
    }

    @Test
    void publicKeyRoundTrip() {
        KeyPair kp = CryptoService.generateKeyPair();
        String b64 = CryptoService.publicKeyBase64(kp.getPublic());
        PublicKey restored = CryptoService.publicKeyFromBase64(b64);
        assertEquals(kp.getPublic(), restored);
    }

    @Test
    void privateKeyRoundTrip() {
        KeyPair kp = CryptoService.generateKeyPair();
        String b64 = CryptoService.privateKeyBase64(kp.getPrivate());
        var restored = CryptoService.privateKeyFromBase64(b64);
        assertEquals(kp.getPrivate(), restored);
    }

    @Test
    void publicKeyFromInvalidBase64Throws() {
        assertThrows(IllegalArgumentException.class, () -> CryptoService.publicKeyFromBase64("not-a-key"));
    }

    @Test
    void privateKeyFromInvalidBase64Throws() {
        assertThrows(IllegalArgumentException.class, () -> CryptoService.privateKeyFromBase64("not-a-key"));
    }
}
