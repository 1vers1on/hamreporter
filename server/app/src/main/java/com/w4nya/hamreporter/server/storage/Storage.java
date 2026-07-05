package com.w4nya.hamreporter.server.storage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.w4nya.hamreporter.server.CallsignRegistry;
import com.w4nya.hamreporter.server.NodeRole;
import com.w4nya.hamreporter.server.PeerInfo;
import com.w4nya.hamreporter.server.SignalReport;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class Storage {
    private static final Logger logger = LoggerFactory.getLogger(Storage.class);
    private final SqliteDatabaseManager databaseManager;
    private final NodeRole nodeRole;
    private final Duration retention;
    private final ObjectMapper objectMapper;

    public Storage(SqliteDatabaseManager databaseManager, NodeRole nodeRole, Duration retention, ObjectMapper objectMapper) {
        this.databaseManager = databaseManager;
        this.nodeRole = nodeRole;
        this.retention = retention;
        this.objectMapper = objectMapper;

        logger.info("Initializing Storage with nodeRole={} retention={}ms", nodeRole, retention.toMillis());
        initSchema();
        logger.info("Storage initialization complete");
    }

    private void initSchema() {
        logger.info("Loading database schema from /schema.sql");
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            if (in == null) {
                logger.error("schema.sql resource not found on classpath");
                throw new IllegalStateException("schema.sql resource not found");
            }
            String schemaSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            logger.debug("Executing schema initialization SQL ({} bytes)", schemaSql.length());
            String[] statements = schemaSql.split(";");
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    databaseManager.executeUpdate(trimmed);
                }
            }
            logger.info("Database schema initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize database schema", e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    public boolean storeReport(SignalReport report) {
        logger.debug(
                "Storing signal report: hash={} originServer={} originSeq={} reporter={} heard={} freq={} mode={} snr={}",
                report.hash(), report.originServer(), report.originSeq(),
                report.reporterCallsign(), report.heardCallsign(),
                report.frequencyHz(), report.mode(), report.snrDb());
        String insertSql = """
                INSERT OR IGNORE INTO signal_reports
                (hash, origin_server, origin_seq, reporter_callsign, reporter_grid, reporter_public_key,
                 heard_callsign, heard_grid, frequency_hz, mode, snr_db, reported_at_epoch_ms,
                 received_at_epoch_ms, extra_json, signature, origin_signature, inserted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try {
            boolean stored = databaseManager.executeUpdate(insertSql, report.hash(),
                    report.originServer(),
                    report.originSeq(),
                    report.reporterCallsign(),
                    report.reporterGrid(),
                    report.reporterPublicKey(),
                    report.heardCallsign(),
                    report.heardGrid(),
                    report.frequencyHz(),
                    report.mode(),
                    report.snrDb(),
                    report.reportedAtEpochMs(),
                    report.receivedAtEpochMs(),
                    objectMapper.writeValueAsString(report.extra()),
                    report.signature(),
                    report.originSignature(),
                    System.currentTimeMillis()) > 0;
            if (stored) {
                logger.info("Signal report stored successfully: hash={} reporter={} heard={} freq={} mode={}",
                        report.hash(), report.reporterCallsign(), report.heardCallsign(),
                        report.frequencyHz(), report.mode());
            } else {
                logger.debug("Signal report already exists (duplicate ignored): hash={}", report.hash());
            }
            return stored;
        } catch (Exception e) {
            logger.error("Failed to store signal report: hash={} reporter={} heard={}",
                    report.hash(), report.reporterCallsign(), report.heardCallsign(), e);
            throw new RuntimeException("Failed to store signal report", e);
        }
    }

    public boolean hasReport(String hash) {
        logger.debug("Checking existence of signal report: hash={}", hash);
        String querySql = "SELECT 1 FROM signal_reports WHERE hash=?";
        try {
            boolean exists = databaseManager.executeQuery(querySql, rs -> rs.next(), hash);
            logger.debug("Signal report existence check: hash={} exists={}", hash, exists);
            return exists;
        } catch (Exception e) {
            logger.error("Failed to check existence of signal report with hash: {}", hash, e);
            throw new RuntimeException("Failed to check existence of signal report", e);
        }
    }

    public SignalReport getReport(String hash) {
        logger.debug("Retrieving signal report: hash={}", hash);
        String querySql = "SELECT * FROM signal_reports WHERE hash=?";
        try {
            SignalReport report = databaseManager.executeQuery(querySql, rs -> {
                if (rs.next()) {
                    return mapResultSetToSignalReport(rs);
                }
                return null;
            }, hash);
            if (report != null) {
                logger.debug("Signal report found: hash={} reporter={} heard={}", hash,
                        report.reporterCallsign(), report.heardCallsign());
            } else {
                logger.debug("Signal report not found: hash={}", hash);
            }
            return report;
        } catch (Exception e) {
            logger.error("Failed to retrieve signal report with hash: {}", hash, e);
            throw new RuntimeException("Failed to retrieve signal report", e);
        }
    }

    public List<SignalReport> queryReports(ReportQuery q) {
        logger.debug(
                "Querying reports: reporter={} heard={} freqMin={} freqMax={} mode={} reporterGrid={} heardGrid={} since={} until={} sort={} limit={}",
                q.reporterCallsign, q.heardCallsign, q.frequencyHzMin, q.frequencyHzMax,
                q.mode, q.reporterGridPrefix, q.heardGridPrefix, q.sinceMs, q.untilMs, q.sort, q.limit);
        StringBuilder sql = new StringBuilder("SELECT * FROM signal_reports WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (q.reporterCallsign != null) {
            sql.append(" AND reporter_callsign=?");
            args.add(q.reporterCallsign.toUpperCase(Locale.ROOT));
        }
        if (q.heardCallsign != null) {
            sql.append(" AND heard_callsign=?");
            args.add(q.heardCallsign.toUpperCase(Locale.ROOT));
        }
        if (q.frequencyHzMin != null) {
            sql.append(" AND frequency_hz>=?");
            args.add(q.frequencyHzMin);
        }
        if (q.frequencyHzMax != null) {
            sql.append(" AND frequency_hz<=?");
            args.add(q.frequencyHzMax);
        }
        if (q.mode != null) {
            sql.append(" AND mode=?");
            args.add(q.mode.toUpperCase(Locale.ROOT));
        }
        if (q.reporterGridPrefix != null) {
            sql.append(" AND reporter_grid LIKE ? ESCAPE '\\\\'");
            args.add(escapeLike(q.reporterGridPrefix.toUpperCase(Locale.ROOT)) + "%");
        }
        if (q.heardGridPrefix != null) {
            sql.append(" AND heard_grid LIKE ? ESCAPE '\\\\'");
            args.add(escapeLike(q.heardGridPrefix.toUpperCase(Locale.ROOT)) + "%");
        }
        if (q.sinceMs != null) {
            sql.append(" AND received_at_epoch_ms>=?");
            args.add(q.sinceMs);
        }
        if (q.untilMs != null) {
            sql.append(" AND received_at_epoch_ms<=?");
            args.add(q.untilMs);
        }

        sql.append(" ORDER BY received_at_epoch_ms ").append("asc".equalsIgnoreCase(q.sort) ? "ASC" : "DESC");
        sql.append(" LIMIT ?");
        args.add(q.limit);

        logger.debug("Executing report query SQL: {} with {} args", sql, args.size());
        try {
            List<SignalReport> results = databaseManager.executeQuery(sql.toString(), rs -> {
                List<SignalReport> reports = new ArrayList<>();
                while (rs.next()) {
                    reports.add(mapResultSetToSignalReport(rs));
                }
                return reports;
            }, args.toArray());

            logger.info("Report query returned {} results", results.size());
            return results;
        } catch (SQLException e) {
            logger.error("Failed to query reports", e);
            throw new RuntimeException("Failed to query reports", e);
        }
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public List<SignalReport> fetchSince(String originServer, long sinceSeq, int limit) {
        logger.debug("Fetching reports since: originServer={} sinceSeq={} limit={}", originServer, sinceSeq, limit);
        String sql = "SELECT * FROM signal_reports WHERE origin_server=? AND origin_seq>? ORDER BY origin_seq ASC LIMIT ?";
        try {
            List<SignalReport> results = databaseManager.executeQuery(sql, rs -> {
                List<SignalReport> reports = new ArrayList<>();
                while (rs.next()) {
                    reports.add(mapResultSetToSignalReport(rs));
                }
                return reports;
            }, originServer, sinceSeq, limit);
            logger.info("Fetched {} reports from originServer={} since seq={}", results.size(), originServer, sinceSeq);
            return results;
        } catch (SQLException e) {
            logger.error("Failed to fetch reports since: originServer={} sinceSeq={}", originServer, sinceSeq, e);
            throw new RuntimeException("Failed to fetch reports", e);
        }
    }

    public long getMaxSeq(String originServer) {
        logger.debug("Fetching max seq for originServer={}", originServer);
        String sql = "SELECT MAX(origin_seq) AS max_seq FROM signal_reports WHERE origin_server=?";
        try {
            Long max = databaseManager.executeQuery(sql, rs -> {
                if (rs.next()) {
                    long val = rs.getLong("max_seq");
                    return rs.wasNull() ? null : val;
                }
                return null;
            }, originServer);
            return max != null ? max : 0L;
        } catch (SQLException e) {
            logger.error("Failed to get max seq for originServer={}", originServer, e);
            return 0L;
        }
    }

    public Map<String, Long> knownOrigins() throws SQLException {
        logger.debug("Fetching known origins");
        String sql = "SELECT origin_server, MAX(origin_seq) AS max_seq FROM signal_reports GROUP BY origin_server";
        Map<String, Long> origins = databaseManager.executeQuery(sql, rs -> {
            Map<String, Long> m = new HashMap<>();
            while (rs.next()) {
                m.put(rs.getString("origin_server"), rs.getLong("max_seq"));
            }
            return m;
        });
        logger.info("Known origins: {} servers - {}", origins.size(), origins);
        return origins;
    }

    public long nextSeq(String originServer) {
        logger.debug("Generating next sequence for origin server: {}", originServer);
        String sql = """
                INSERT INTO seq_counters(origin_server, last_seq) VALUES(?, 1)
                ON CONFLICT(origin_server) DO UPDATE SET last_seq = last_seq + 1
                RETURNING last_seq
                """;

        try {
            long seq = databaseManager.executeQuery(sql, rs -> {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Failed to update sequence counter for server: " + originServer);
            }, originServer);

            logger.debug("Generated next sequence: originServer={} seq={}", originServer, seq);
            return seq;

        } catch (Exception e) {
            logger.error("Failed to generate next sequence for origin server: {}", originServer, e);
            throw new RuntimeException("Failed to generate next sequence", e);
        }
    }

    public void touchCallsign(String callsign, String publicKey, String originServer, Long lastSeq) {
        logger.debug("Touching callsign: callsign={} originServer={} lastSeq={}", callsign, originServer, lastSeq);
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO callsign_registry(callsign, public_key, last_seen_epoch_ms, origin_server, last_report_seq, registered_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(callsign) DO UPDATE SET
                    public_key=CASE WHEN callsign_registry.public_key IS NULL THEN excluded.public_key ELSE callsign_registry.public_key END,
                    last_seen_epoch_ms=excluded.last_seen_epoch_ms,
                    origin_server=excluded.origin_server,
                    last_report_seq=COALESCE(excluded.last_report_seq, callsign_registry.last_report_seq)
                """;
        try {
            databaseManager.executeUpdate(sql, callsign.toUpperCase(Locale.ROOT), publicKey, now, originServer,
                    lastSeq, now);
            logger.info("Callsign touched: callsign={} originServer={} lastSeq={}", callsign, originServer, lastSeq);
        } catch (Exception e) {
            logger.error("Failed to touch callsign: {} originServer={} lastSeq={}", callsign, originServer, lastSeq, e);
            throw new RuntimeException("Failed to touch callsign", e);
        }
    }

    public List<CallsignRegistry.Entry> activeCallsigns(long sinceMs) {
        logger.debug("Fetching active callsigns since epochMs={}", sinceMs);
        String sql = "SELECT * FROM callsign_registry WHERE last_seen_epoch_ms>=? ORDER BY last_seen_epoch_ms DESC";
        try {
            List<CallsignRegistry.Entry> results = databaseManager.executeQuery(sql, rs -> {
                List<CallsignRegistry.Entry> out = new ArrayList<>();
                while (rs.next()) {
                    long lastReportSeq = rs.getLong("last_report_seq");
                    out.add(new CallsignRegistry.Entry(
                            rs.getString("callsign"),
                            rs.getString("public_key"),
                            rs.getLong("last_seen_epoch_ms"),
                            rs.getString("origin_server"),
                            rs.wasNull() ? null : lastReportSeq));
                }
                return out;
            }, sinceMs);
            logger.info("Fetched {} active callsigns since epochMs={}", results.size(), sinceMs);
            return results;
        } catch (Exception e) {
            logger.error("Failed to fetch active callsigns since epochMs={}", sinceMs, e);
            throw new RuntimeException("Failed to fetch active callsigns", e);
        }
    }

    public String getCallsignKey(String callsign) {
        logger.debug("Fetching public key for callsign: {}", callsign);
        String sql = "SELECT public_key FROM callsign_registry WHERE callsign=?";
        try {
            String key = databaseManager.executeQuery(sql, rs -> {
                if (rs.next()) {
                    return rs.getString("public_key");
                }
                return null;
            }, callsign.toUpperCase(Locale.ROOT));
            if (key != null) {
                logger.debug("Public key found for callsign: {}", callsign);
            } else {
                logger.debug("No public key found for callsign: {}", callsign);
            }
            return key;
        } catch (Exception e) {
            logger.error("Failed to fetch public key for callsign: {}", callsign, e);
            throw new RuntimeException("Failed to fetch public key for callsign", e);
        }
    }

    public void upsertPeer(PeerInfo pi) {
        logger.debug("Upserting peer: nodeId={} callsign={} baseUrl={} role={} trusted={}",
                pi.nodeId(), pi.callsign(), pi.baseUrl(), pi.role(), pi.trusted());
        String sql = """
                INSERT INTO peer_directory(node_id, callsign, base_url, public_key, role, last_seen, trusted)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(node_id) DO UPDATE SET
                  callsign=excluded.callsign,
                  base_url=excluded.base_url,
                  public_key=excluded.public_key,
                  role=excluded.role,
                  last_seen=excluded.last_seen,
                  trusted=excluded.trusted
                """;
        try {
            databaseManager.executeUpdate(sql, pi.nodeId(), pi.callsign(), pi.baseUrl(),
                    pi.publicKeyB64(), safeRoleName(pi.role()), pi.lastSeen(), pi.trusted());
            logger.info("Peer upserted: nodeId={} callsign={} baseUrl={} role={} trusted={}",
                    pi.nodeId(), pi.callsign(), pi.baseUrl(), pi.role(), pi.trusted());
        } catch (Exception e) {
            logger.error("Failed to upsert peer: nodeId={} callsign={} baseUrl={}",
                    pi.nodeId(), pi.callsign(), pi.baseUrl(), e);
            throw new RuntimeException("Failed to upsert peer", e);
        }
    }

    public PeerInfo getPeer(String nodeId) {
        logger.debug("Fetching peer by nodeId: {}", nodeId);
        String sql = "SELECT * FROM peer_directory WHERE node_id=?";
        try {
            PeerInfo peer = databaseManager.executeQuery(sql, rs -> {
                if (rs.next()) {
                    return mapPeerRow(rs);
                }
                return null;
            }, nodeId);
            if (peer != null) {
                logger.debug("Peer found: nodeId={} callsign={} baseUrl={}", nodeId, peer.callsign(), peer.baseUrl());
            } else {
                logger.debug("Peer not found: nodeId={}", nodeId);
            }
            return peer;
        } catch (Exception e) {
            logger.error("Failed to fetch peer info for node_id: {}", nodeId, e);
            throw new RuntimeException("Failed to fetch peer info", e);
        }
    }

    public PeerInfo getPeerByUrl(String url) {
        logger.debug("Fetching peer by base_url: {}", url);
        String sql = "SELECT * FROM peer_directory WHERE base_url=?";
        try {
            PeerInfo peer = databaseManager.executeQuery(sql, rs -> {
                if (rs.next()) {
                    return mapPeerRow(rs);
                }
                return null;
            }, url);
            if (peer != null) {
                logger.debug("Peer found by URL: url={} nodeId={} callsign={}", url, peer.nodeId(), peer.callsign());
            } else {
                logger.debug("Peer not found by URL: url={}", url);
            }
            return peer;
        } catch (Exception e) {
            logger.error("Failed to fetch peer info for base_url: {}", url, e);
            throw new RuntimeException("Failed to fetch peer info", e);
        }
    }

    public List<PeerInfo> allPeers() {
        logger.debug("Fetching all peers");
        String sql = "SELECT * FROM peer_directory ORDER BY last_seen DESC";
        try {
            List<PeerInfo> peers = databaseManager.executeQuery(sql, rs -> {
                List<PeerInfo> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapPeerRow(rs));
                }
                return result;
            });
            logger.info("Fetched {} peers", peers.size());
            return peers;
        } catch (Exception e) {
            logger.error("Failed to fetch all peers", e);
            throw new RuntimeException("Failed to fetch all peers", e);
        }
    }

    public long getWatermark(String peerNodeId, String originServer) {
        logger.debug("Fetching watermark: peerNodeId={} originServer={}", peerNodeId, originServer);
        String sql = "SELECT last_seq FROM peer_watermarks WHERE peer_node_id=? AND origin_server=?";
        try {
            long watermark = databaseManager.executeQuery(sql, rs -> {
                if (rs.next()) {
                    return rs.getLong("last_seq");
                }
                return 0L;
            }, peerNodeId, originServer);
            logger.debug("Watermark found: peerNodeId={} originServer={} seq={}", peerNodeId, originServer, watermark);
            return watermark;
        } catch (Exception e) {
            logger.debug("No watermark found for peer={} and origin={}, returning 0", peerNodeId, originServer);
            return 0L;
        }
    }

    public void setWatermark(String peerNodeId, String originServer, long seq) {
        logger.debug("Setting watermark: peerNodeId={} originServer={} seq={}", peerNodeId, originServer, seq);
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO peer_watermarks(peer_node_id, origin_server, last_seq, updated_at)
                VALUES(?,?,?,?)
                ON CONFLICT(peer_node_id, origin_server) DO UPDATE SET
                  last_seq=excluded.last_seq,
                  updated_at=excluded.updated_at
                """;
        try {
            databaseManager.executeUpdate(sql, peerNodeId, originServer, seq, now);
            logger.info("Watermark set: peerNodeId={} originServer={} seq={}", peerNodeId, originServer, seq);
        } catch (Exception e) {
            logger.error("Failed to set watermark for peer: {} and origin: {} seq={}", peerNodeId, originServer, seq,
                    e);
            throw new RuntimeException("Failed to set watermark", e);
        }
    }

    public void audit(String event, String actor, String detail) {
        logger.debug("Recording audit entry: event={} actor={} detail={}", event, actor, detail);
        String sql = "INSERT INTO audit_log(event, actor, detail, timestamp) VALUES(?,?,?,?)";
        try {
            databaseManager.executeUpdate(sql, event, actor, detail, System.currentTimeMillis());
            logger.info("Audit entry recorded: event={} actor={} detail={}", event, actor, detail);
        } catch (Exception e) {
            logger.error("Failed to insert audit log entry: event={} actor={} detail={}", event, actor, detail, e);
            throw new RuntimeException("Failed to insert audit log entry", e);
        }
    }

    public boolean checkAndStoreNonce(String nonce, long expiresAt) {
        logger.debug("Checking and storing nonce: nonce={} expiresAt={}", nonce, expiresAt);
        String sql = "INSERT OR IGNORE INTO seen_nonces(nonce, expires_at) VALUES(?, ?)";
        try {
            int rowsAffected = databaseManager.executeUpdate(sql, nonce, expiresAt);
            boolean isNew = rowsAffected > 0;
            if (isNew) {
                logger.debug("Nonce accepted (new): nonce={}", nonce);
            } else {
                logger.warn("Nonce rejected (duplicate): nonce={}", nonce);
            }
            return isNew;
        } catch (Exception e) {
            logger.error("Failed to check and store nonce: {}", nonce, e);
            throw new RuntimeException("Failed to check and store nonce", e);
        }
    }

    public void purgeExpiredNonces(long now) {
        logger.debug("Purging expired nonces with cutoff epochMs={}", now);
        String sql = "DELETE FROM seen_nonces WHERE expires_at < ?";
        try {
            int purged = databaseManager.executeUpdate(sql, now);
            logger.info("Purged {} expired nonces", purged);
        } catch (Exception e) {
            logger.error("Failed to purge expired nonces", e);
            throw new RuntimeException("Failed to purge expired nonces", e);
        }
    }

    public int enforceRetention() {
        if (nodeRole == NodeRole.ARCHIVE) {
            logger.debug("Skipping retention enforcement on ARCHIVE node");
            return 0;
        }

        long cutoff = System.currentTimeMillis() - retention.toMillis();
        logger.info("Enforcing retention policy: cutoff epochMs={} retentionMs={}", cutoff, retention.toMillis());
        String sql = "DELETE FROM signal_reports WHERE received_at_epoch_ms < ?";

        try {
            int deleted = databaseManager.executeUpdate(sql, cutoff);
            logger.info("Retention enforcement complete: {} reports deleted with cutoff epochMs={}", deleted, cutoff);
            return deleted;
        } catch (Exception e) {
            logger.error("Failed to enforce retention policy with cutoff: {}", cutoff, e);
            throw new RuntimeException("Failed to enforce retention policy", e);
        }
    }

    private SignalReport mapResultSetToSignalReport(ResultSet rs) throws SQLException {
        Map<String, String> extra = Map.of();
        String ej = rs.getString("extra_json");
        if (ej != null && !ej.isBlank() && !ej.equals("{}")) {
            try {
                extra = objectMapper.readValue(ej, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception e) {
                logger.warn("Failed to parse extra_json, defaulting to empty map: {}", ej, e);
            }
        }
        Double snr = rs.getObject("snr_db", Double.class);
        return new SignalReport(
                rs.getString("hash"),
                rs.getString("origin_server"),
                rs.getLong("origin_seq"),
                rs.getString("reporter_callsign"),
                rs.getString("reporter_grid"),
                rs.getString("reporter_public_key"),
                rs.getString("heard_callsign"),
                rs.getString("heard_grid"),
                rs.getLong("frequency_hz"),
                rs.getString("mode"),
                snr,
                rs.getLong("reported_at_epoch_ms"),
                rs.getLong("received_at_epoch_ms"),
                extra,
                rs.getString("signature"),
                rs.getString("origin_signature"));
    }

    private PeerInfo mapPeerRow(ResultSet rs) throws SQLException {
        return new PeerInfo(
                rs.getString("node_id"),
                rs.getString("callsign"),
                rs.getString("base_url"),
                rs.getString("public_key"),
                safeNodeRole(rs.getString("role")),
                rs.getLong("last_seen"),
                rs.getBoolean("trusted"));
    }

    private static NodeRole safeNodeRole(String name) {
        try {
            return NodeRole.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return NodeRole.ROLLING;
        }
    }

    private static String safeRoleName(NodeRole role) {
        return role != null ? role.name() : NodeRole.ROLLING.name();
    }
}
