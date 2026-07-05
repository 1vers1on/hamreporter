package com.w4nya.hamreporter.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

public class SignalReportTest {

    @Test
    void callsignNormalizedToUppercase() {
        SignalReport r = new SignalReport("h", "n", 1, "w1aw", "FN31", "key",
                "k1abc", "FM18", 14074000L, "ft8", -10.0,
                1000L, 2000L, Map.of(), "sig", "osig");
        assertEquals("W1AW", r.reporterCallsign());
        assertEquals("K1ABC", r.heardCallsign());
    }

    @Test
    void modeNormalizedToUppercase() {
        SignalReport r = new SignalReport("h", "n", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "ft8", -10.0,
                1000L, 2000L, Map.of(), "sig", "osig");
        assertEquals("FT8", r.mode());
    }

    @Test
    void callsignTrimmed() {
        SignalReport r = new SignalReport("h", "n", 1, " W1AW ", "FN31", "key",
                " K1ABC ", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", "osig");
        assertEquals("W1AW", r.reporterCallsign());
        assertEquals("K1ABC", r.heardCallsign());
    }

    @Test
    void nullExtraDefaultsToEmpty() {
        SignalReport r = new SignalReport("h", "n", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, null, "sig", "osig");
        assertEquals(Map.of(), r.extra());
    }

    @Test
    void isOriginatedReturnsTrueWhenOriginSignaturePresent() {
        SignalReport r = new SignalReport("h", "node1", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", "osig");
        assertTrue(r.isOriginated());
    }

    @Test
    void isOriginatedReturnsFalseWhenNoOriginSignature() {
        SignalReport r = new SignalReport("h", "node1", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", null);
        assertFalse(r.isOriginated());
    }

    @Test
    void isOriginatedReturnsFalseWhenBlankOriginSignature() {
        SignalReport r = new SignalReport("h", "node1", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", "   ");
        assertFalse(r.isOriginated());
    }

    @Test
    void canonicalReporterPayloadFormat() {
        SignalReport r = new SignalReport("h", "n", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", "osig");
        String canon = r.canonicalReporterPayload();
        assertTrue(canon.contains("reporterCallsign=W1AW"));
        assertTrue(canon.contains("heardCallsign=K1ABC"));
        assertTrue(canon.contains("frequencyHz=14074000"));
        assertTrue(canon.contains("mode=FT8"));
    }

    @Test
    void canonicalOriginPayloadFormat() {
        SignalReport r = new SignalReport("h", "node1", 42, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", "osig");
        String canon = r.canonicalOriginPayload();
        assertEquals("node1|42|h|2000", canon);
    }

    @Test
    void withOriginCreatesNewInstance() {
        SignalReport r = new SignalReport("h", null, 0, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 0L, Map.of(), "sig", null);
        SignalReport originated = r.withOrigin("node1", 1L, 2000L, "newhash");
        assertEquals("node1", originated.originServer());
        assertEquals(1L, originated.originSeq());
        assertEquals("newhash", originated.hash());
        assertEquals(2000L, originated.receivedAtEpochMs());
    }

    @Test
    void withOriginSignatureCreatesNewInstance() {
        SignalReport r = new SignalReport("h", "n", 1, "W1AW", "FN31", "key",
                "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                1000L, 2000L, Map.of(), "sig", null);
        SignalReport signed = r.withOriginSignature("newOsig");
        assertEquals("newOsig", signed.originSignature());
    }
}
