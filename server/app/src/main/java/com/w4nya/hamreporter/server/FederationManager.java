package com.w4nya.hamreporter.server;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.w4nya.hamreporter.server.storage.Storage;
import com.w4nya.hamreporter.server.util.CryptoVerifier;
import com.w4nya.hamreporter.server.util.HttpSignature;
import com.w4nya.hamreporter.server.util.RateLimiter;

import tools.jackson.databind.ObjectMapper;

public class FederationManager {
    private static final Logger logger = LoggerFactory.getLogger(FederationManager.class);
    private final NodeIdentity identity;
    private final Storage storage;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ScheduledExecutorService sched;
    private final RateLimiter limiter;
    private final CryptoVerifier verifier;
    private final int syncBatchSize;

    public FederationManager(NodeIdentity identity, Storage storage, ObjectMapper mapper,
            RateLimiter limiter, CryptoVerifier verifier,
            Duration syncInterval, int syncBatchSize) {
        this.identity = identity;
        this.storage = storage;
        this.mapper = mapper;
        this.limiter = limiter;
        this.verifier = verifier;
        this.syncBatchSize = syncBatchSize;

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.sched = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "federation");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(Duration syncInterval) {
        long syncIntervalSec = Math.max(5, syncInterval.getSeconds());
        sched.scheduleWithFixedDelay(this::syncAll, 5, syncIntervalSec, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(this::gossipAnnouncements, 3, 5, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(storage::enforceRetention, 60, 300, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(this::purgeNonces, 60, 300, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(limiter::cleanup, 60, 300, TimeUnit.SECONDS);

        logger.info(
                "FederationManager started: nodeId={} callsign={} baseUrl={} role={} syncInterval={}s syncBatchSize={}",
                identity.nodeId(), identity.callsign, identity.baseUrl, identity.role,
                syncIntervalSec, syncBatchSize);
    }

    public void connectToConfiguredPeers(List<String> peerUrls, Map<String, String> trustedPeerKeys) {
        for (String url : peerUrls) {
            try {
                String key = trustedPeerKeys.get(url);
                boolean trustOnFirstUse = key != null;
                PeerInfo peer = new PeerInfo(
                        deriveNodeId(url),
                        null, url, key != null ? key : "", NodeRole.ROLLING,
                        System.currentTimeMillis(), trustOnFirstUse);
                sched.submit(() -> {
                    try {
                        hello(peer, trustOnFirstUse);
                    } catch (Exception e) {
                        logger.warn("Failed initial hello to configured peer url={}: {}", url, e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.warn("Failed to queue initial hello for peer url={}: {}", url, e.getMessage());
            }
        }
    }

    private static String deriveNodeId(String url) {
        return url.replaceAll("^https?://", "").replaceAll("[/:].*$", "") + ":" +
                url.replaceAll("^.*:", "").replaceAll("/.*$", "");
    }

    public void shutdown() {
        logger.info("Shutting down federation manager: nodeId={}", identity.nodeId());
        sched.shutdownNow();
        try {
            if (!sched.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Federation manager did not terminate within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("Federation manager shutdown complete: nodeId={}", identity.nodeId());
    }

    public void gossipReport(SignalReport r) {
        if (!r.isOriginated()) {
            logger.debug("Skipping gossip for non-originated report: hash={}", r.hash());
            return;
        }
        logger.debug("Gossiping report: hash={} reporter={} heard={} originServer={} originSeq={}",
                r.hash(), r.reporterCallsign(), r.heardCallsign(), r.originServer(), r.originSeq());
        try {
            byte[] body = mapper.writeValueAsBytes(r);
            List<PeerInfo> peers = storage.allPeers();
            int targetCount = 0;
            for (PeerInfo peer : peers) {
                if (peer.nodeId().equals(identity.nodeId()))
                    continue;
                targetCount++;
                sched.submit(() -> pushReportToPeer(peer, body));
            }
            logger.debug("Gossip submitted to {} peers for report hash={}", targetCount, r.hash());
        } catch (Exception e) {
            logger.warn("Failed to gossip report hash={}: {}", r.hash(), e.getMessage(), e);
        }
    }

    private void pushReportToPeer(PeerInfo peer, byte[] body) {
        logger.debug("Pushing report to peer: nodeId={} baseUrl={}", peer.nodeId(), peer.baseUrl());
        try {
            String path = "/api/v1/federation/announce";
            long ts = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString();
            String canon = HttpSignature.canonical("POST", path, ts, nonce, body);
            String sig = identity.sign(canon);
            HttpRequest req = HttpRequest.newBuilder(URI.create(peer.baseUrl() + path))
                    .header("Content-Type", "application/json")
                    .header("X-Node-Id", identity.nodeId())
                    .header("X-Callsign", identity.callsign)
                    .header("X-Timestamp", String.valueOf(ts))
                    .header("X-Nonce", nonce)
                    .header("X-Signature", sig)
                    .header("X-Public-Key", identity.publicKeyB64)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 409) {
                logger.debug("Peer already has report (409): nodeId={}", peer.nodeId());
            } else if (resp.statusCode() / 100 != 2) {
                logger.warn("Unexpected response pushing report to peer nodeId={}: status={}", peer.nodeId(),
                        resp.statusCode());
            } else {
                logger.debug("Report pushed successfully to peer: nodeId={} status={}", peer.nodeId(),
                        resp.statusCode());
            }
        } catch (Exception e) {
            logger.warn("Failed to push report to peer nodeId={} baseUrl={}: {}", peer.nodeId(), peer.baseUrl(),
                    e.getMessage(), e);
        }
    }

    private void syncAll() {
        logger.debug("Starting sync cycle for all peers");
        try {
            Map<String, Long> have = storage.knownOrigins();
            logger.debug("Known origins for sync: {} servers - {}", have.size(), have);
            List<PeerInfo> peers = storage.allPeers();
            int syncCount = 0;
            for (PeerInfo peer : peers) {
                if (peer.nodeId().equals(identity.nodeId()))
                    continue;
                syncCount++;
                sched.submit(() -> syncFromPeer(peer, have));
            }
            logger.debug("Submitted sync tasks for {} peers", syncCount);
        } catch (Exception e) {
            logger.warn("Failed to initiate sync cycle: {}", e.getMessage(), e);
        }
    }

    private void syncFromPeer(PeerInfo peer, Map<String, Long> have) {
        logger.debug("Syncing from peer: nodeId={} baseUrl={}", peer.nodeId(), peer.baseUrl());
        try {
            for (var entry : have.entrySet()) {
                long ourMax = entry.getValue();
                long watermark = storage.getWatermark(peer.nodeId(), entry.getKey());
                long since = watermark > 0 ? watermark : ourMax;
                logger.debug("Syncing origin={} from peer={}: ourMax={} watermark={} since={}",
                        entry.getKey(), peer.nodeId(), ourMax, watermark, since);
                pullBatchLoop(peer, entry.getKey(), since);
            }
            pullOriginsList(peer);
            logger.debug("Sync completed from peer: nodeId={}", peer.nodeId());
        } catch (Exception e) {
            logger.warn("Failed to sync from peer nodeId={} baseUrl={}: {}", peer.nodeId(), peer.baseUrl(),
                    e.getMessage(), e);
        }
    }

    private void pullOriginsList(PeerInfo peer) throws Exception {
        logger.debug("Pulling origins list from peer: nodeId={} baseUrl={}", peer.nodeId(), peer.baseUrl());
        String path = "/api/v1/federation/origins";
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canon = HttpSignature.canonical("GET", path, ts, nonce, null);
        String sig = identity.sign(canon);
        HttpRequest req = HttpRequest.newBuilder(URI.create(peer.baseUrl() + path))
                .header("X-Node-Id", identity.nodeId())
                .header("X-Callsign", identity.callsign)
                .header("X-Timestamp", String.valueOf(ts))
                .header("X-Nonce", nonce)
                .header("X-Signature", sig)
                .header("X-Public-Key", identity.publicKeyB64)
                .timeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            logger.warn("Failed to pull origins list from peer nodeId={}: status={}", peer.nodeId(), resp.statusCode());
            return;
        }
        Map<String, Long> origins = mapper.readValue(resp.body(),
                mapper.getTypeFactory().constructMapType(HashMap.class, String.class, Long.class));
        logger.debug("Received origins list from peer nodeId={}: {} servers - {}", peer.nodeId(), origins.size(),
                origins);

        for (var e : origins.entrySet()) {
            long ourMax = storage.getMaxSeq(e.getKey());
            logger.debug("Origin comparison: origin={} peerMax={} ourMax={} peer={}",
                    e.getKey(), e.getValue(), ourMax, peer.nodeId());
            if (e.getValue() > ourMax) {
                logger.info("Peer nodeId={} has newer data for origin={}: peerMax={} ourMax={}, pulling batch",
                        peer.nodeId(), e.getKey(), e.getValue(), ourMax);
                pullBatchLoop(peer, e.getKey(), ourMax);
            }
        }
    }

    private void pullBatchLoop(PeerInfo peer, String originServer, long sinceSeq) throws Exception {
        long currentSince = sinceSeq;
        while (true) {
            List<SignalReport> batch = pullBatch(peer, originServer, currentSince);
            if (batch == null || batch.isEmpty()) break;

            long maxSeq = currentSince;
            for (SignalReport r : batch) {
                if (r.originSeq() > maxSeq) maxSeq = r.originSeq();
            }
            storage.setWatermark(peer.nodeId(), originServer, maxSeq);

            if (batch.size() < syncBatchSize) break;
            currentSince = maxSeq;
        }
    }

    private List<SignalReport> pullBatch(PeerInfo peer, String originServer, long sinceSeq) throws Exception {
        logger.debug("Pulling batch from peer: nodeId={} origin={} sinceSeq={} batchSize={}",
                peer.nodeId(), originServer, sinceSeq, syncBatchSize);
        String path = "/api/v1/federation/since?origin="
                + URLEncoder.encode(originServer, StandardCharsets.UTF_8)
                + "&since=" + sinceSeq + "&limit=" + syncBatchSize;
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canon = HttpSignature.canonical("GET", path, ts, nonce, null);
        String sig = identity.sign(canon);
        HttpRequest req = HttpRequest.newBuilder(URI.create(peer.baseUrl() + path))
                .header("X-Node-Id", identity.nodeId())
                .header("X-Callsign", identity.callsign)
                .header("X-Timestamp", String.valueOf(ts))
                .header("X-Nonce", nonce)
                .header("X-Signature", sig)
                .header("X-Public-Key", identity.publicKeyB64)
                .timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            logger.warn("Failed to pull batch from peer nodeId={} origin={} since={}: status={}",
                    peer.nodeId(), originServer, sinceSeq, resp.statusCode());
            return null;
        }
        List<SignalReport> batch = mapper.readValue(resp.body(),
                mapper.getTypeFactory().constructCollectionType(List.class, SignalReport.class));
        logger.debug("Received batch of {} reports from peer nodeId={} origin={} since={}",
                batch.size(), peer.nodeId(), originServer, sinceSeq);

        int stored = 0;
        int rejected = 0;
        int failed = 0;
        for (SignalReport r : batch) {
            if (!verifier.verifyReportLenient(r)) {
                logger.warn(
                        "Signature verification failed for report hash={} origin={} originSeq={} from peer nodeId={}",
                        r.hash(), r.originServer(), r.originSeq(), peer.nodeId());
                rejected++;
                continue;
            }
            try {
                if (storage.storeReport(r)) {
                    storage.touchCallsign(r.reporterCallsign(), r.reporterPublicKey(), r.originServer(), r.originSeq());
                    stored++;
                }
            } catch (Exception e) {
                logger.warn("Failed to store report hash={} from peer nodeId={}: {}", r.hash(), peer.nodeId(),
                        e.getMessage());
                failed++;
            }
        }
        logger.info(
                "Batch pull result from peer nodeId={} origin={}: received={} stored={} rejected={} failed={}",
                peer.nodeId(), originServer, batch.size(), stored, rejected, failed);

        return batch;
    }

    private void gossipAnnouncements() {
        logger.trace("Gossip announcements tick (no-op)");
    }

    private void purgeNonces() {
        logger.debug("Running scheduled nonce purge");
        try {
            storage.purgeExpiredNonces(System.currentTimeMillis());
        } catch (Exception e) {
            logger.warn("Failed to purge expired nonces: {}", e.getMessage(), e);
        }
    }

    public void hello(PeerInfo peer, boolean trustOnFirstUse) throws Exception {
        logger.info("Initiating hello handshake with peer: nodeId={} baseUrl={} trustOnFirstUse={}",
                peer.nodeId(), peer.baseUrl(), trustOnFirstUse);

        logger.debug("Fetching peer info from: {}/api/v1/nodes/info", peer.baseUrl());
        HttpRequest req = HttpRequest.newBuilder(URI.create(peer.baseUrl() + "/api/v1/nodes/info"))
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            logger.error("Peer info request failed: nodeId={} baseUrl={} status={}",
                    peer.nodeId(), peer.baseUrl(), resp.statusCode());
            throw new RuntimeException("peer info failed: " + resp.statusCode());
        }
        PeerInfo info = mapper.readValue(resp.body(), PeerInfo.class);
        logger.debug("Received peer info: nodeId={} callsign={} baseUrl={} role={}",
                info.nodeId(), info.callsign(), info.baseUrl(), info.role());

        if (trustOnFirstUse && peer.publicKeyB64() != null && !peer.publicKeyB64().isEmpty()
                && !peer.publicKeyB64().equals(info.publicKeyB64())) {
            logger.error("Peer public key mismatch for nodeId={}: expected={} got={}",
                    info.nodeId(), peer.publicKeyB64(), info.publicKeyB64());
            throw new RuntimeException("peer public key mismatch");
        }

        boolean trusted = trustOnFirstUse;
        storage.upsertPeer(new PeerInfo(info.nodeId(), info.callsign(), info.baseUrl(), info.publicKeyB64(),
                info.role(), System.currentTimeMillis(), trusted));
        logger.info("Peer upserted: nodeId={} callsign={} trusted={}", info.nodeId(), info.callsign(), trusted);

        String path = "/api/v1/federation/hello";
        Map<String, Object> body = Map.of(
                "nodeId", identity.nodeId(),
                "callsign", identity.callsign,
                "baseUrl", identity.baseUrl,
                "publicKey", identity.publicKeyB64,
                "role", identity.role.name());
        byte[] bodyBytes = mapper.writeValueAsBytes(body);
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canon = HttpSignature.canonical("POST", path, ts, nonce, bodyBytes);
        String sig = identity.sign(canon);
        HttpRequest post = HttpRequest.newBuilder(URI.create(peer.baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("X-Node-Id", identity.nodeId())
                .header("X-Callsign", identity.callsign)
                .header("X-Timestamp", String.valueOf(ts))
                .header("X-Nonce", nonce)
                .header("X-Signature", sig)
                .header("X-Public-Key", identity.publicKeyB64)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .timeout(Duration.ofSeconds(15)).build();
        logger.debug("Sending hello POST to peer: nodeId={} path={}", info.nodeId(), path);
        HttpResponse<Void> helloResp = http.send(post, HttpResponse.BodyHandlers.discarding());
        if (helloResp.statusCode() / 100 != 2) {
            logger.warn("Hello POST to peer nodeId={} returned non-2xx status: {}", info.nodeId(),
                    helloResp.statusCode());
        } else {
            logger.info("Hello handshake completed successfully with peer: nodeId={} callsign={}", info.nodeId(),
                    info.callsign());
        }
    }
}
