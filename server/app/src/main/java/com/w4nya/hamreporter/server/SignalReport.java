package com.w4nya.hamreporter.server;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignalReport(
        String hash,
        String originServer,
        long originSeq,
        String reporterCallsign,
        String reporterGrid,
        String reporterPublicKey,
        String heardCallsign,
        String heardGrid,
        long frequencyHz,
        String mode,
        Double snrDb,
        long reportedAtEpochMs,
        long receivedAtEpochMs,
        Map<String, String> extra,
        String signature,
        String originSignature) {

    public SignalReport {
        extra = extra == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(extra));
        if (reporterCallsign != null)
            reporterCallsign = reporterCallsign.toUpperCase(Locale.ROOT).trim();
        if (heardCallsign != null)
            heardCallsign = heardCallsign.toUpperCase(Locale.ROOT).trim();
        if (mode != null)
            mode = mode.toUpperCase(Locale.ROOT).trim();
    }

    public void validate() {
        Objects.requireNonNull(hash, "hash must not be null");
        Objects.requireNonNull(reporterCallsign, "reporterCallsign must not be null");
        Objects.requireNonNull(reporterPublicKey, "reporterPublicKey must not be null");
        Objects.requireNonNull(heardCallsign, "heardCallsign must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        if (frequencyHz <= 0) {
            throw new IllegalArgumentException("frequencyHz must be positive, got: " + frequencyHz);
        }
        if (reportedAtEpochMs <= 0) {
            throw new IllegalArgumentException("reportedAtEpochMs must be positive");
        }
    }

    public SignalReport withOrigin(String originServer, long originSeq, long receivedAtEpochMs, String hash) {
        return new SignalReport(hash, originServer, originSeq, reporterCallsign, reporterGrid,
                reporterPublicKey, heardCallsign, heardGrid, frequencyHz, mode, snrDb,
                reportedAtEpochMs, receivedAtEpochMs, extra, signature, originSignature);
    }

    public SignalReport withSignature(String signature) {
        return new SignalReport(hash, originServer, originSeq, reporterCallsign, reporterGrid,
                reporterPublicKey, heardCallsign, heardGrid, frequencyHz, mode, snrDb,
                reportedAtEpochMs, receivedAtEpochMs, extra, signature, originSignature);
    }

    public SignalReport withOriginSignature(String originSignature) {
        return new SignalReport(hash, originServer, originSeq, reporterCallsign, reporterGrid,
                reporterPublicKey, heardCallsign, heardGrid, frequencyHz, mode, snrDb,
                reportedAtEpochMs, receivedAtEpochMs, extra, signature, originSignature);
    }

    @JsonIgnore
    public String canonicalReporterPayload() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("reporterCallsign=").append(reporterCallsign).append('\n');
        sb.append("reporterGrid=").append(reporterGrid).append('\n');
        sb.append("reporterPublicKey=").append(reporterPublicKey).append('\n');
        sb.append("heardCallsign=").append(heardCallsign).append('\n');
        sb.append("heardGrid=").append(heardGrid).append('\n');
        sb.append("frequencyHz=").append(frequencyHz).append('\n');
        sb.append("mode=").append(mode).append('\n');
        sb.append("snrDb=").append(snrDb == null ? "null" : snrDb).append('\n');
        sb.append("reportedAtEpochMs=").append(reportedAtEpochMs).append('\n');
        sb.append("extra=");
        if (extra.isEmpty()) {
            sb.append("{}");
        } else {
            sb.append('{');
            boolean first = true;
            for (var e : extra.entrySet()) {
                if (!first)
                    sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append('}');
        }
        return sb.toString();
    }

    @JsonIgnore
    public String canonicalOriginPayload() {
        return originServer + "|" + originSeq + "|" + hash + "|" + receivedAtEpochMs;
    }

    @JsonIgnore
    public boolean isOriginated() {
        return originServer != null && originSignature != null && !originSignature.isBlank();
    }
}
