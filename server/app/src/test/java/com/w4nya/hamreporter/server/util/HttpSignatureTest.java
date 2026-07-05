package com.w4nya.hamreporter.server.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class HttpSignatureTest {

    @Test
    void canonicalProducesDeterministicOutput() {
        byte[] body = "test body".getBytes();
        String c1 = HttpSignature.canonical("POST", "/api/test", 12345L, "nonce1", body);
        String c2 = HttpSignature.canonical("POST", "/api/test", 12345L, "nonce1", body);
        assertEquals(c1, c2);
    }

    @Test
    void canonicalMethodUppercased() {
        byte[] body = new byte[0];
        String c = HttpSignature.canonical("post", "/path", 1L, "n", body);
        assertTrue(c.startsWith("POST\n"));
    }

    @Test
    void canonicalWithNullBodyUsesEmpty() {
        String c1 = HttpSignature.canonical("GET", "/path", 1L, "n", null);
        String c2 = HttpSignature.canonical("GET", "/path", 1L, "n", new byte[0]);
        assertEquals(c1, c2);
    }

    @Test
    void canonicalDiffersForDifferentBodies() {
        String c1 = HttpSignature.canonical("POST", "/path", 1L, "n", "body1".getBytes());
        String c2 = HttpSignature.canonical("POST", "/path", 1L, "n", "body2".getBytes());
        assertNotEquals(c1, c2);
    }

    @Test
    void canonicalFormat() {
        byte[] body = "hello".getBytes();
        String c = HttpSignature.canonical("POST", "/api/v1/reports", 1000L, "abc", body);
        String[] parts = c.split("\n", 5);
        assertEquals("POST", parts[0]);
        assertEquals("/api/v1/reports", parts[1]);
        assertEquals("1000", parts[2]);
        assertEquals("abc", parts[3]);
        assertFalse(parts[4].isEmpty());
    }
}
