package com.w4nya.hamreporter.server.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class ReportQueryTest {

    @Test
    void fromParamsWithAllFields() {
        Map<String, List<String>> params = new HashMap<>();
        params.put("reporter", List.of("W1AW"));
        params.put("heard", List.of("K9XYZ"));
        params.put("freqMin", List.of("14000000"));
        params.put("freqMax", List.of("15000000"));
        params.put("mode", List.of("FT8"));
        params.put("reporterGrid", List.of("FN"));
        params.put("heardGrid", List.of("FM"));
        params.put("since", List.of("1000"));
        params.put("until", List.of("2000"));
        params.put("limit", List.of("50"));
        params.put("sort", List.of("asc"));
        ReportQuery q = ReportQuery.fromParams(params);
        assertEquals("W1AW", q.reporterCallsign);
        assertEquals("K9XYZ", q.heardCallsign);
        assertEquals(14000000L, q.frequencyHzMin);
        assertEquals(15000000L, q.frequencyHzMax);
        assertEquals("FT8", q.mode);
        assertEquals("FN", q.reporterGridPrefix);
        assertEquals("FM", q.heardGridPrefix);
        assertEquals(1000L, q.sinceMs);
        assertEquals(2000L, q.untilMs);
        assertEquals(50, q.limit);
        assertEquals("asc", q.sort);
    }

    @Test
    void fromParamsDefaults() {
        ReportQuery q = ReportQuery.fromParams(Map.of());
        assertNull(q.reporterCallsign);
        assertNull(q.heardCallsign);
        assertNull(q.frequencyHzMin);
        assertNull(q.frequencyHzMax);
        assertNull(q.mode);
        assertNull(q.reporterGridPrefix);
        assertNull(q.heardGridPrefix);
        assertNull(q.sinceMs);
        assertNull(q.untilMs);
        assertEquals(200, q.limit);
        assertEquals("desc", q.sort);
    }

    @Test
    void limitClampedToMax1000() {
        Map<String, List<String>> params = Map.of("limit", List.of("9999"));
        ReportQuery q = ReportQuery.fromParams(params);
        assertEquals(1000, q.limit);
    }

    @Test
    void limitClampedToMin1() {
        Map<String, List<String>> params = Map.of("limit", List.of("0"));
        ReportQuery q = ReportQuery.fromParams(params);
        assertEquals(1, q.limit);
    }
}
