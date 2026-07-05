package com.w4nya.hamreporter.server.storage;

import java.util.List;
import java.util.Map;

public class ReportQuery {
    public String reporterCallsign;
    public String heardCallsign;
    public Long frequencyHzMin;
    public Long frequencyHzMax;
    public String mode;
    public String reporterGridPrefix;
    public String heardGridPrefix;
    public Long sinceMs;
    public Long untilMs;
    public Integer limit = 200;
    public String sort = "desc";

    public static ReportQuery fromParams(Map<String, List<String>> p) {
        ReportQuery q = new ReportQuery();
        q.reporterCallsign = first(p, "reporter");
        q.heardCallsign = first(p, "heard");
        q.frequencyHzMin = optLong(p, "freqMin");
        q.frequencyHzMax = optLong(p, "freqMax");
        q.mode = first(p, "mode");
        q.reporterGridPrefix = first(p, "reporterGrid");
        q.heardGridPrefix = first(p, "heardGrid");
        q.sinceMs = optLong(p, "since");
        q.untilMs = optLong(p, "until");
        String lim = first(p, "limit");
        if (lim != null) {
            try {
                q.limit = Math.min(1000, Math.max(1, Integer.parseInt(lim)));
            } catch (NumberFormatException e) {
                q.limit = 200;
            }
        }
        String sort = first(p, "sort");
        if (sort != null) {
            if ("asc".equalsIgnoreCase(sort) || "desc".equalsIgnoreCase(sort)) {
                q.sort = sort.toLowerCase();
            }
        }
        return q;
    }

    private static String first(Map<String, List<String>> p, String k) {
        var l = p.get(k);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    private static Long optLong(Map<String, List<String>> p, String k) {
        String s = first(p, k);
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
