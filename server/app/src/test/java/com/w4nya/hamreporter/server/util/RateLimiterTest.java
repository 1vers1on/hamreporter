package com.w4nya.hamreporter.server.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RateLimiterTest {
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(60, 1200, 3);
    }

    @Test
    void allowCallsignWithinBurst() {
        assertTrue(limiter.allowCallsign("W1AW"));
        assertTrue(limiter.allowCallsign("W1AW"));
        assertTrue(limiter.allowCallsign("W1AW"));
    }

    @Test
    void allowCallsignRejectsAfterBurstExhausted() {
        limiter.allowCallsign("W1AW");
        limiter.allowCallsign("W1AW");
        limiter.allowCallsign("W1AW");
        assertFalse(limiter.allowCallsign("W1AW"));
    }

    @Test
    void allowCallsignIndependentPerCallsign() {
        limiter.allowCallsign("W1AW");
        limiter.allowCallsign("W1AW");
        limiter.allowCallsign("W1AW");
        assertTrue(limiter.allowCallsign("K9XYZ"));
    }

    @Test
    void allowCallsignRejectsNull() {
        assertFalse(limiter.allowCallsign(null));
    }

    @Test
    void allowCallsignRejectsBlank() {
        assertFalse(limiter.allowCallsign("   "));
    }

    @Test
    void allowIpWithinBurst() {
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertTrue(limiter.allowIp("127.0.0.1"));
        assertFalse(limiter.allowIp("127.0.0.1"));
    }

    @Test
    void allowIpRejectsNull() {
        assertFalse(limiter.allowIp(null));
    }

    @Test
    void cleanupRemovesExpiredBuckets() {
        limiter.allowCallsign("W1AW");
        limiter.cleanup();
    }
}
