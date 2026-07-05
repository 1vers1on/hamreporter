package com.w4nya.hamreporter.server.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.w4nya.hamreporter.server.NodeRole;
import com.w4nya.hamreporter.server.PeerInfo;
import com.w4nya.hamreporter.server.SignalReport;

import tools.jackson.databind.json.JsonMapper;

public class StorageTest {
    private SqliteDatabaseManager db;
    private Storage storage;

    @BeforeEach
    void setUp() {
        db = new SqliteDatabaseManager(":memory:");
        storage = new Storage(db, NodeRole.ROLLING, Duration.ofDays(7), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private SignalReport sampleReport(String hash, String originServer, long originSeq) {
        return new SignalReport(
                hash, originServer, originSeq,
                "W1AW", "FN31", "cHViS2V5", "K1ABC", "FM18",
                14074000L, "FT8", -10.0,
                System.currentTimeMillis(), System.currentTimeMillis(),
                Map.of(), "c2ln", "b3JpZ1NpZw==");
    }

    @Test
    void schemaInitializesAllTables() throws SQLException {
        String[] tables = {"signal_reports", "callsign_registry", "peer_watermarks",
                "peer_directory", "seq_counters", "audit_log", "seen_nonces"};
        for (String table : tables) {
            db.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    rs -> {
                        assertTrue(rs.next(), "Table " + table + " should exist");
                        return null;
                    }, table);
        }
    }

    @Test
    void storeAndRetrieveReport() {
        SignalReport report = sampleReport("hash1", "node1", 1L);
        assertTrue(storage.storeReport(report));
        assertTrue(storage.hasReport("hash1"));
        assertFalse(storage.hasReport("nonexistent"));
    }

    @Test
    void storeDuplicateReportIgnored() {
        SignalReport report = sampleReport("hash1", "node1", 1L);
        assertTrue(storage.storeReport(report));
        assertFalse(storage.storeReport(report));
    }

    @Test
    void getReportReturnsCorrectData() {
        SignalReport report = sampleReport("hash1", "node1", 1L);
        storage.storeReport(report);
        SignalReport retrieved = storage.getReport("hash1");
        assertNotNull(retrieved);
        assertEquals("hash1", retrieved.hash());
        assertEquals("W1AW", retrieved.reporterCallsign());
        assertEquals("K1ABC", retrieved.heardCallsign());
    }

    @Test
    void getReportReturnsNullForMissing() {
        assertNull(storage.getReport("nonexistent"));
    }

    @Test
    void queryReportsByCallsign() throws SQLException {
        storage.storeReport(sampleReport("h1", "node1", 1));
        SignalReport r2 = sampleReport("h2", "node1", 2);
        r2 = new SignalReport("h2", "node1", 2, "K9XYZ", "FM18", "cHViS2V5",
                "W1AW", "FN31", 14074000L, "FT8", -10.0,
                System.currentTimeMillis(), System.currentTimeMillis(), Map.of(), "c2ln", "b3JpZ1NpZw==");
        storage.storeReport(r2);

        ReportQuery q = new ReportQuery();
        q.reporterCallsign = "W1AW";
        List<SignalReport> results = storage.queryReports(q);
        assertEquals(1, results.size());
        assertEquals("W1AW", results.get(0).reporterCallsign());
    }

    @Test
    void queryReportsByFrequencyRange() throws SQLException {
        storage.storeReport(sampleReport("h1", "node1", 1));
        ReportQuery q = new ReportQuery();
        q.frequencyHzMin = 14000000L;
        q.frequencyHzMax = 14100000L;
        List<SignalReport> results = storage.queryReports(q);
        assertEquals(1, results.size());

        q.frequencyHzMin = 15000000L;
        results = storage.queryReports(q);
        assertTrue(results.isEmpty());
    }

    @Test
    void fetchSinceReturnsNewerReports() throws SQLException {
        storage.storeReport(sampleReport("h1", "node1", 1));
        storage.storeReport(sampleReport("h2", "node1", 2));
        List<SignalReport> results = storage.fetchSince("node1", 0, 10);
        assertEquals(2, results.size());
        results = storage.fetchSince("node1", 1, 10);
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).originSeq());
    }

    @Test
    void knownOriginsReturnsMap() throws SQLException {
        storage.storeReport(sampleReport("h1", "node1", 1));
        storage.storeReport(sampleReport("h2", "node2", 1));
        Map<String, Long> origins = storage.knownOrigins();
        assertEquals(2, origins.size());
        assertEquals(1L, origins.get("node1"));
        assertEquals(1L, origins.get("node2"));
    }

    @Test
    void nextSeqIncrements() {
        long s1 = storage.nextSeq("node1");
        long s2 = storage.nextSeq("node1");
        assertEquals(1, s1);
        assertEquals(2, s2);
    }

    @Test
    void nextSeqIndependentPerServer() {
        long s1 = storage.nextSeq("node1");
        long s2 = storage.nextSeq("node2");
        assertEquals(1, s1);
        assertEquals(1, s2);
    }

    @Test
    void touchCallsignAndRetrieve() {
        storage.touchCallsign("W1AW", "key1", "node1", 1L);
        assertEquals("key1", storage.getCallsignKey("W1AW"));
    }

    @Test
    void touchCallsignPreservesExistingKey() {
        storage.touchCallsign("W1AW", "key1", "node1", 1L);
        storage.touchCallsign("W1AW", "key2", "node1", 2L);
        assertEquals("key1", storage.getCallsignKey("W1AW"));
    }

    @Test
    void touchCallsignSetsKeyWhenNull() {
        storage.touchCallsign("W1AW", "key1", "node1", 1L);
        assertEquals("key1", storage.getCallsignKey("W1AW"));
    }

    @Test
    void getCallsignKeyReturnsNullForMissing() {
        assertNull(storage.getCallsignKey("NOBODY"));
    }

    @Test
    void activeCallsignsReturnsRecent() {
        storage.touchCallsign("W1AW", "key1", "node1", 1L);
        List<?> active = storage.activeCallsigns(0);
        assertEquals(1, active.size());
    }

    @Test
    void upsertAndGetPeer() {
        PeerInfo peer = new PeerInfo("node1", "W1AW", "http://localhost:8080",
                "cHViS2V5", NodeRole.ROLLING, System.currentTimeMillis(), true);
        storage.upsertPeer(peer);
        PeerInfo retrieved = storage.getPeer("node1");
        assertNotNull(retrieved);
        assertEquals("node1", retrieved.nodeId());
        assertEquals("W1AW", retrieved.callsign());
    }

    @Test
    void upsertPeerUpdatesExisting() {
        PeerInfo peer = new PeerInfo("node1", "W1AW", "http://localhost:8080",
                "cHViS2V5", NodeRole.ROLLING, System.currentTimeMillis(), false);
        storage.upsertPeer(peer);
        PeerInfo updated = new PeerInfo("node1", "W1AW", "http://localhost:9090",
                "cHViS2V5", NodeRole.ARCHIVE, System.currentTimeMillis(), true);
        storage.upsertPeer(updated);
        PeerInfo retrieved = storage.getPeer("node1");
        assertEquals("http://localhost:9090", retrieved.baseUrl());
        assertEquals(NodeRole.ARCHIVE, retrieved.role());
        assertTrue(retrieved.trusted());
    }

    @Test
    void allPeersReturnsList() {
        storage.upsertPeer(new PeerInfo("n1", "W1AW", "http://a", "k1", NodeRole.ROLLING,
                System.currentTimeMillis(), true));
        storage.upsertPeer(new PeerInfo("n2", "K9XYZ", "http://b", "k2", NodeRole.ARCHIVE,
                System.currentTimeMillis(), false));
        List<PeerInfo> peers = storage.allPeers();
        assertEquals(2, peers.size());
    }

    @Test
    void getPeerByUrlReturnsCorrectPeer() {
        storage.upsertPeer(new PeerInfo("n1", "W1AW", "http://localhost:8080", "k1",
                NodeRole.ROLLING, System.currentTimeMillis(), true));
        PeerInfo found = storage.getPeerByUrl("http://localhost:8080");
        assertNotNull(found);
        assertEquals("n1", found.nodeId());
    }

    @Test
    void watermarkDefaultsToZero() {
        assertEquals(0L, storage.getWatermark("peer1", "origin1"));
    }

    @Test
    void setAndGetWatermark() {
        storage.setWatermark("peer1", "origin1", 42L);
        assertEquals(42L, storage.getWatermark("peer1", "origin1"));
    }

    @Test
    void watermarkUpdatesValue() {
        storage.setWatermark("peer1", "origin1", 10L);
        storage.setWatermark("peer1", "origin1", 20L);
        assertEquals(20L, storage.getWatermark("peer1", "origin1"));
    }

    @Test
    void auditLogRecordsEntries() {
        assertDoesNotThrow(() -> storage.audit("test.event", "actor1", "detail1"));
    }

    @Test
    void checkAndStoreNonceAcceptsNew() {
        assertTrue(storage.checkAndStoreNonce("nonce1", System.currentTimeMillis() + 60000));
    }

    @Test
    void checkAndStoreNonceRejectsDuplicate() {
        storage.checkAndStoreNonce("nonce1", System.currentTimeMillis() + 60000);
        assertFalse(storage.checkAndStoreNonce("nonce1", System.currentTimeMillis() + 60000));
    }

    @Test
    void purgeExpiredNoncesRemovesOld() {
        long past = System.currentTimeMillis() - 1000;
        storage.checkAndStoreNonce("expired", past);
        storage.purgeExpiredNonces(System.currentTimeMillis());
        assertTrue(storage.checkAndStoreNonce("expired", System.currentTimeMillis() + 60000));
    }

    @Test
    void enforceRetentionDeletesOldReports() {
        long oldTime = System.currentTimeMillis() - Duration.ofDays(8).toMillis();
        SignalReport oldReport = new SignalReport("old1", "node1", 1, "W1AW", "FN31",
                "cHViS2V5", "K1ABC", "FM18", 14074000L, "FT8", -10.0,
                oldTime, oldTime, Map.of(), "c2ln", "b3JpZ1NpZw==");
        storage.storeReport(oldReport);
        storage.storeReport(sampleReport("new1", "node1", 2));
        int deleted = storage.enforceRetention();
        assertEquals(1, deleted);
        assertFalse(storage.hasReport("old1"));
        assertTrue(storage.hasReport("new1"));
    }

    @Test
    void enforceRetentionSkipsOnArchiveNode() {
        SqliteDatabaseManager archiveDb = new SqliteDatabaseManager(":memory:");
        Storage archiveStorage = new Storage(archiveDb, NodeRole.ARCHIVE, Duration.ofDays(7), JsonMapper.builder().build());
        assertEquals(0, archiveStorage.enforceRetention());
        archiveDb.close();
    }

    @Test
    void extraJsonParseFailureDefaultsToEmptyMap() throws Exception {
        SignalReport report = sampleReport("h1", "node1", 1);
        storage.storeReport(report);
        db.executeUpdate("UPDATE signal_reports SET extra_json = 'invalid json' WHERE hash = 'h1'");
        SignalReport retrieved = storage.getReport("h1");
        assertNotNull(retrieved);
        assertEquals(Map.of(), retrieved.extra());
    }
}
