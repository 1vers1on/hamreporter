package com.w4nya.hamreporter.server.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.w4nya.hamreporter.server.NodeRole;
import com.w4nya.hamreporter.server.util.CryptoService;

public class NodeConfig {
    public String nodeId = CryptoService.randomHex(16);
    public String callsign = "NOCALL";
    public String bindHost = "0.0.0.0";
    public int port = 45271;
    public String privateKeyPath = "private.key";
    public String publicKeyPath = "public.key";
    public NodeRole role = NodeRole.ROLLING;
    public String retention = "7d";
    public String baseUrl = "http://localhost:45271";

    public List<String> peers = new ArrayList<>(List.of("http://134.199.250.68:45271"));
    public Map<String, String> trustedPeerKeys = new HashMap<>(Map.of(
        "http://134.199.250.68:45271", "MCowBQYDK2VwAyEAZaqPIGcLRapdrXTb8F+DWgF3/fI6tJpoVklNvBY8C/g="
    ));
    public int perCallsignReportsPerHour = 60;
    public int perIpReportsPerHour = 1200;
    public int burstReports = 10;

    public String callsignTtl = "15m";
    public String syncInterval = "30s";
    public String gossipInterval = "5s";
    public int syncBatchSize = 500;

    public int maxRequestBodyBytes = 256 * 1024;
    public int maxClockSkewSeconds = 300;

    public boolean trustForwardedHeaders = false;

    public Duration retentionDuration() {
        return parseDuration(retention);
    }

    public Duration callsignTtlDuration() {
        return parseDuration(callsignTtl);
    }

    public Duration syncIntervalDuration() {
        return parseDuration(syncInterval);
    }

    public Duration gossipIntervalDuration() {
        return parseDuration(gossipInterval);
    }

    private static final Pattern DURATION_RE = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);

    private static Duration parseDuration(String s) {
        String up = s.trim().toUpperCase(Locale.ROOT);
        if (up.startsWith("P")) {
            return java.time.Duration.parse(up);
        }
        long days = 0, hours = 0, minutes = 0, seconds = 0;
        Matcher m = DURATION_RE.matcher(up);
        boolean found = false;
        while (m.find()) {
            found = true;
            long val = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "D" -> days = val;
                case "H" -> hours = val;
                case "M" -> minutes = val;
                case "S" -> seconds = val;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Invalid duration: '" + s + "'");
        }
        return java.time.Duration.ofDays(days).plusHours(hours).plusMinutes(minutes).plusSeconds(seconds);
    }

    public void validate() {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (!nodeId.matches("[0-9a-fA-F]{16}")) {
            throw new IllegalArgumentException("nodeId must be a 16-character hex string");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535, got: " + port);
        }
        if (perCallsignReportsPerHour < 0) {
            throw new IllegalArgumentException("perCallsignReportsPerHour must be non-negative");
        }
        if (perIpReportsPerHour < 0) {
            throw new IllegalArgumentException("perIpReportsPerHour must be non-negative");
        }
        if (burstReports < 1) {
            throw new IllegalArgumentException("burstReports must be at least 1");
        }
        if (maxClockSkewSeconds < 1) {
            throw new IllegalArgumentException("maxClockSkewSeconds must be at least 1");
        }
        if (syncBatchSize < 1) {
            throw new IllegalArgumentException("syncBatchSize must be at least 1");
        }
        if (maxRequestBodyBytes < 1024) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be at least 1024");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
    }
}
