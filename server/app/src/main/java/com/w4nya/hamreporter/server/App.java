package com.w4nya.hamreporter.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.help.HelpFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.w4nya.hamreporter.server.config.ConfigManager;
import com.w4nya.hamreporter.server.config.DataPathResolver;
import com.w4nya.hamreporter.server.config.NodeConfig;
import com.w4nya.hamreporter.server.storage.ReportQuery;
import com.w4nya.hamreporter.server.storage.SqliteDatabaseManager;
import com.w4nya.hamreporter.server.storage.Storage;
import com.w4nya.hamreporter.server.util.CryptoService;
import com.w4nya.hamreporter.server.util.CryptoVerifier;
import com.w4nya.hamreporter.server.util.RateLimiter;

import io.javalin.Javalin;
import io.javalin.http.Context;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private static volatile App instance;
    private static final Object instanceLock = new Object();

    public static App getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new App();
                }
            }
        }
        return instance;
    }

    private Path configPath;
    private Path dataPath;

    private SqliteDatabaseManager databaseManager;
    private Storage storage;

    private NodeConfig nodeConfig;

    private Javalin server;

    private CryptoVerifier verifier;
    private RateLimiter limiter;
    private FederationManager federation;

    private NodeIdentity nodeIdentity;

    private ObjectMapper mapper;

    private volatile boolean shutdownComplete = false;

    public void run(String[] args) {
        logger.info("Starting HamReporter server...");

        try {
            setupCommandLineOptions(args);
        } catch (Exception e) {
            logger.error("Error parsing command line options", e);
            stop(1);
        }

        ensureDirectories();

        ensureAndLoadConfig();

        loadNodeIdentity();

        String dbFilePath = dataPath.resolve("hamreporter.db").toString();

        try {
            databaseManager = new SqliteDatabaseManager(dbFilePath);
        } catch (Exception e) {
            logger.error("Failed to initialize the database", e);
            stop(1);
        }

        mapper = JsonMapper.builder()
                .build();

        storage = new Storage(databaseManager, nodeConfig.role, nodeConfig.retentionDuration(), mapper);

        verifier = new CryptoVerifier(storage);

        limiter = new RateLimiter(nodeConfig.perCallsignReportsPerHour, nodeConfig.perIpReportsPerHour,
                nodeConfig.burstReports);

        federation = new FederationManager(nodeIdentity, storage, mapper, limiter, verifier,
                nodeConfig.syncIntervalDuration(), nodeConfig.syncBatchSize);

        server = Javalin.create(config -> {
            config.http.maxRequestSize = nodeConfig.maxRequestBodyBytes;

            config.jsonMapper(new io.javalin.json.JsonMapper() {
                @Override
                public String toJsonString(Object obj, java.lang.reflect.Type type) {
                    try {
                        return mapper.writeValueAsString(obj);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public <T> T fromJsonString(String json, java.lang.reflect.Type type) {
                    try {
                        return mapper.readValue(json, mapper.constructType(type));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            config.routes.before(ctx -> {
                ctx.header("X-Node-Id", nodeConfig.nodeId);
                ctx.header("Access-Control-Allow-Origin", "*");
            });

            config.routes.get("/api/v1/health", ctx -> {
                ctx.json(Map.of("status", "ok", "nodeId", nodeConfig.nodeId));
            });

            config.routes.get("/api/v1/nodes/info", ctx -> {
                PeerInfo info = new PeerInfo(nodeConfig.nodeId, nodeConfig.callsign, nodeConfig.baseUrl,
                        nodeIdentity.publicKeyB64, nodeConfig.role, System.currentTimeMillis(), true);
                ctx.json(info);
            });

            config.routes.get("/api/v1/nodes/peers", ctx -> {
                ctx.json(storage.allPeers());
            });

            config.routes.get("/api/v1/callsigns/active", ctx -> {
                long since = System.currentTimeMillis() - nodeConfig.callsignTtlDuration().toMillis();
                ctx.json(storage.activeCallsigns(since));
            });

            config.routes.post("/api/v1/reports", this::submitReport);

            config.routes.get("/api/v1/reports", this::queryReports);
            config.routes.get("/api/v1/reports/{hash}", ctx -> {
                String hash = ctx.pathParam("hash");
                if (!HASH_PATTERN.matcher(hash).matches()) {
                    ctx.status(400).json(Map.of("error", "invalid hash format"));
                    return;
                }
                SignalReport r = storage.getReport(hash);
                if (r == null) {
                    ctx.status(404).json(Map.of("error", "report not found"));
                    return;
                }
                ctx.json(r);
            });

            config.routes.post("/api/v1/heartbeat", this::heartbeat);

            config.routes.post("/api/v1/federation/announce", this::federationAnnounce);
            config.routes.get("/api/v1/federation/origins", this::federationOrigins);
            config.routes.get("/api/v1/federation/since", this::federationSince);
            config.routes.post("/api/v1/federation/hello", this::federationHello);
        });

        try {
            server.start(nodeConfig.port);
        } catch (Exception e) {
            logger.error("Failed to start server on port {}", nodeConfig.port, e);
            stop(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

        federation.start(nodeConfig.syncIntervalDuration());
        federation.connectToConfiguredPeers(nodeConfig.peers, nodeConfig.trustedPeerKeys);

        logger.info("HamReporter server started on {}:{} with nodeId={}", nodeConfig.bindHost, nodeConfig.port,
                nodeConfig.nodeId);
    }

    private String resolveIp(Context ctx) {
        if (nodeConfig.trustForwardedHeaders) {
            String forwarded = ctx.header("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String realIp = ctx.header("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return ctx.ip();
    }

    private void submitReport(Context ctx) throws Exception {
        String ip = resolveIp(ctx);
        if (!limiter.allowIp(ip)) {
            ctx.status(429).json(Map.of("error", "ip rate limit"));
            return;
        }

        SignedRequestContext sr = readSigned(ctx);
        if (sr == null) {
            ctx.status(401).json(Map.of("error", "missing signature headers"));
            return;
        }
        if (!verifyTimestamp(sr)) {
            ctx.status(401).json(Map.of("error", "clock skew"));
            return;
        }
        if (!verifyNonce(sr)) {
            ctx.status(401).json(Map.of("error", "replay"));
            return;
        }

        if (!verifier.verifyHttpSignature(sr, "POST", ctx.path())) {
            ctx.status(401).json(Map.of("error", "bad signature"));
            return;
        }

        SignalReport incoming;
        try {
            incoming = mapper.readValue(sr.body(), SignalReport.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON body"));
            return;
        }

        try {
            incoming.validate();
        } catch (IllegalArgumentException | NullPointerException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
            return;
        }

        if (!incoming.reporterCallsign().equalsIgnoreCase(sr.callsign())) {
            ctx.status(401).json(Map.of("error", "callsign mismatch"));
            return;
        }
        if (!incoming.reporterPublicKey().equals(sr.publicKeyB64())) {
            ctx.status(401).json(Map.of("error", "public key mismatch"));
            return;
        }

        if (!verifier.verifyReport(incoming)) {
            ctx.status(401).json(Map.of("error", "invalid report signature"));
            return;
        }

        String recomputed = CryptoService.sha256Hex(incoming.canonicalReporterPayload());
        if (!recomputed.equals(incoming.hash())) {
            ctx.status(400).json(Map.of("error", "hash mismatch"));
            return;
        }

        String existingKey = storage.getCallsignKey(incoming.reporterCallsign());
        if (existingKey != null && !existingKey.equals(incoming.reporterPublicKey())) {
            ctx.status(409).json(Map.of("error", "callsign key conflict"));
            return;
        }

        if (!limiter.allowCallsign(incoming.reporterCallsign())) {
            ctx.status(429).json(Map.of("error", "callsign rate limit"));
            return;
        }

        SignalReport existing = storage.getReport(incoming.hash());
        if (existing != null) {
            ctx.status(200).json(existing);
            return;
        }

        long seq = storage.nextSeq(nodeIdentity.nodeId());
        long now = System.currentTimeMillis();
        SignalReport originated = incoming.withOrigin(nodeIdentity.nodeId(), seq, now, incoming.hash());
        String originSig = nodeIdentity.sign(originated.canonicalOriginPayload());
        originated = originated.withOriginSignature(originSig);

        if (storage.storeReport(originated)) {
            storage.touchCallsign(originated.reporterCallsign(), originated.reporterPublicKey(),
                    originated.originServer(), originated.originSeq());
            storage.audit("report.received", originated.reporterCallsign(), originated.hash());
            federation.gossipReport(originated);
            ctx.status(201).json(originated);
        } else {
            existing = storage.getReport(incoming.hash());
            ctx.status(200).json(existing != null ? existing : originated);
        }
    }

    private void queryReports(Context ctx) {
        ReportQuery q = ReportQuery.fromParams(ctx.queryParamMap());
        try {
            ctx.json(storage.queryReports(q));
        } catch (Exception e) {
            logger.error("Failed to query reports", e);
            ctx.status(500).json(Map.of("error", "internal server error"));
        }
    }

    private SignedRequestContext readSigned(Context ctx) {
        String callsign = ctx.header("X-Callsign");
        String pub = ctx.header("X-Public-Key");
        String ts = ctx.header("X-Timestamp");
        String nonce = ctx.header("X-Nonce");
        String sig = ctx.header("X-Signature");
        byte[] body = ctx.bodyAsBytes();
        if (callsign == null || pub == null || ts == null || nonce == null || sig == null)
            return null;
        try {
            return new SignedRequestContext(callsign.toUpperCase(), pub, Long.parseLong(ts), nonce, sig, body);
        } catch (NumberFormatException e) {
            logger.debug("Invalid timestamp in signed request from ip={}", resolveIp(ctx));
            return null;
        }
    }

    private boolean verifyTimestamp(SignedRequestContext ctx) {
        long now = System.currentTimeMillis();
        long skew = Math.abs(now - ctx.timestamp());
        return skew <= nodeConfig.maxClockSkewSeconds * 1000L;
    }

    private boolean verifyNonce(SignedRequestContext ctx) {
        try {
            long expires = ctx.timestamp() + (nodeConfig.maxClockSkewSeconds * 2L * 1000L);
            return storage.checkAndStoreNonce(ctx.nonce(), expires);
        } catch (Exception e) {
            return false;
        }
    }

    private void heartbeat(Context ctx) throws Exception {
        SignedRequestContext sr = readSigned(ctx);
        if (sr == null) {
            ctx.status(401).json(Map.of("error", "missing signature headers"));
            return;
        }
        if (!verifyTimestamp(sr)) {
            ctx.status(401).json(Map.of("error", "clock skew"));
            return;
        }
        if (!verifyNonce(sr)) {
            ctx.status(401).json(Map.of("error", "replay"));
            return;
        }
        if (!verifier.verifyHttpSignature(sr, "POST", ctx.path())) {
            ctx.status(401).json(Map.of("error", "bad signature"));
            return;
        }

        String existingKey = storage.getCallsignKey(sr.callsign());
        if (existingKey != null && !existingKey.equals(sr.publicKeyB64())) {
            ctx.status(409).json(Map.of("error", "callsign key conflict"));
            return;
        }

        storage.touchCallsign(sr.callsign(), sr.publicKeyB64(), nodeIdentity.nodeId(), null);
        ctx.json(Map.of("ok", true, "serverTime", System.currentTimeMillis()));
    }

    private void federationAnnounce(Context ctx) throws Exception {
        String nodeId = ctx.header("X-Node-Id");
        SignedRequestContext sr = readSigned(ctx);
        if (sr == null || nodeId == null) {
            ctx.status(401).json(Map.of("error", "missing signature headers"));
            return;
        }
        if (!verifyTimestamp(sr)) {
            ctx.status(401).json(Map.of("error", "clock skew"));
            return;
        }
        if (!verifyNonce(sr)) {
            ctx.status(401).json(Map.of("error", "replay"));
            return;
        }

        PeerInfo knownPeer = storage.getPeer(nodeId);
        if (knownPeer != null && !knownPeer.publicKeyB64().equals(sr.publicKeyB64())) {
            ctx.status(401).json(Map.of("error", "public key mismatch for known peer"));
            return;
        }

        if (!verifier.verifyHttpSignature(sr, "POST", ctx.path())) {
            ctx.status(401).json(Map.of("error", "bad signature"));
            return;
        }

        SignalReport report;
        try {
            report = mapper.readValue(sr.body(), SignalReport.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON body"));
            return;
        }

        String recomputed = CryptoService.sha256Hex(report.canonicalReporterPayload());
        if (!recomputed.equals(report.hash())) {
            ctx.status(400).json(Map.of("error", "hash mismatch"));
            return;
        }

        try {
            PublicKey reporterKey = CryptoService.publicKeyFromBase64(report.reporterPublicKey());
            if (!CryptoService.verifyBase64(reporterKey, report.canonicalReporterPayload(), report.signature())) {
                ctx.status(401).json(Map.of("error", "invalid report signature"));
                return;
            }
        } catch (Exception e) {
            ctx.status(401).json(Map.of("error", "invalid reporter key"));
            return;
        }

        if (report.isOriginated()) {
            PeerInfo originPeer = storage.getPeer(report.originServer());
            if (originPeer != null) {
                try {
                    PublicKey originKey = CryptoService.publicKeyFromBase64(originPeer.publicKeyB64());
                    if (!CryptoService.verifyBase64(originKey, report.canonicalOriginPayload(),
                            report.originSignature())) {
                        ctx.status(401).json(Map.of("error", "invalid origin signature"));
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("Could not verify origin signature for report hash={} origin={} from ip={}",
                            report.hash(), report.originServer(), resolveIp(ctx));
                    ctx.status(401).json(Map.of("error", "origin signature verification failed"));
                    return;
                }
            } else {
                logger.warn("Unknown origin server {} for report hash={}, accepting without origin verification from ip={}",
                        report.originServer(), report.hash(), resolveIp(ctx));
            }
        }

        if (storage.storeReport(report)) {
            storage.touchCallsign(report.reporterCallsign(), report.reporterPublicKey(),
                    report.originServer(), report.originSeq());
            storage.audit("federation.report.received", nodeId, report.hash());
            ctx.status(201).json(report);
        } else {
            ctx.status(409).json(Map.of("error", "duplicate"));
        }
    }

    private void federationOrigins(Context ctx) throws Exception {
        String nodeId = ctx.header("X-Node-Id");
        SignedRequestContext sr = readSigned(ctx);
        if (sr == null || nodeId == null) {
            ctx.status(401).json(Map.of("error", "missing signature headers"));
            return;
        }
        if (!verifyTimestamp(sr)) {
            ctx.status(401).json(Map.of("error", "clock skew"));
            return;
        }
        if (!verifyNonce(sr)) {
            ctx.status(401).json(Map.of("error", "replay"));
            return;
        }
        if (!verifier.verifyHttpSignature(sr, "GET", requestPath(ctx))) {
            ctx.status(401).json(Map.of("error", "bad signature"));
            return;
        }
        ctx.json(storage.knownOrigins());
    }

    private void federationSince(Context ctx) throws Exception {
        String nodeId = ctx.header("X-Node-Id");
        SignedRequestContext sr = readSigned(ctx);
        if (sr == null || nodeId == null) {
            ctx.status(401).json(Map.of("error", "missing signature headers"));
            return;
        }
        if (!verifyTimestamp(sr)) {
            ctx.status(401).json(Map.of("error", "clock skew"));
            return;
        }
        if (!verifyNonce(sr)) {
            ctx.status(401).json(Map.of("error", "replay"));
            return;
        }
        if (!verifier.verifyHttpSignature(sr, "GET", requestPath(ctx))) {
            ctx.status(401).json(Map.of("error", "bad signature"));
            return;
        }

        String origin = ctx.queryParam("origin");
        String sinceStr = ctx.queryParam("since");
        String limitStr = ctx.queryParam("limit");
        if (origin == null || sinceStr == null) {
            ctx.status(400).json(Map.of("error", "origin and since are required"));
            return;
        }
        long since;
        int limit;
        try {
            since = Long.parseLong(sinceStr);
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "since must be a number"));
            return;
        }
        try {
            limit = limitStr != null ? Math.min(1000, Math.max(1, Integer.parseInt(limitStr))) : 500;
        } catch (NumberFormatException e) {
            limit = 500;
        }
        List<SignalReport> reports = storage.fetchSince(origin, since, limit);
        ctx.json(reports);
    }

    private void federationHello(Context ctx) throws Exception {
        String nodeId = ctx.header("X-Node-Id");
        SignedRequestContext sr = readSigned(ctx);
        if (sr == null || nodeId == null) {
            ctx.status(401).json(Map.of("error", "missing signature headers"));
            return;
        }
        if (!verifyTimestamp(sr)) {
            ctx.status(401).json(Map.of("error", "clock skew"));
            return;
        }
        if (!verifyNonce(sr)) {
            ctx.status(401).json(Map.of("error", "replay"));
            return;
        }
        if (!verifier.verifyHttpSignature(sr, "POST", ctx.path())) {
            ctx.status(401).json(Map.of("error", "bad signature"));
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(sr.body(), Map.class);

        String peerNodeId = body.get("nodeId") instanceof String s ? s : null;
        String peerCallsign = body.get("callsign") instanceof String s ? s : null;
        String peerBaseUrl = body.get("baseUrl") instanceof String s ? s : null;
        String peerPublicKey = body.get("publicKey") instanceof String s ? s : null;
        String peerRole = body.get("role") instanceof String s ? s : null;

        if (peerNodeId == null || peerBaseUrl == null || peerPublicKey == null) {
            ctx.status(400).json(Map.of("error", "nodeId, baseUrl, and publicKey are required"));
            return;
        }

        PeerInfo existing = storage.getPeer(peerNodeId);
        boolean trusted = existing != null && existing.trusted();

        NodeRole role;
        try {
            role = peerRole != null ? NodeRole.valueOf(peerRole) : NodeRole.ROLLING;
        } catch (IllegalArgumentException e) {
            role = NodeRole.ROLLING;
        }

        PeerInfo newPeer = new PeerInfo(
                peerNodeId,
                peerCallsign,
                peerBaseUrl,
                peerPublicKey,
                role,
                System.currentTimeMillis(),
                trusted);

        storage.upsertPeer(newPeer);
        verifier.invalidatePeerKey(peerNodeId);
        storage.audit("federation.hello", peerNodeId, peerBaseUrl);

        logger.info("Federation hello received and peer upserted: nodeId={} callsign={} baseUrl={}",
                peerNodeId, peerCallsign, peerBaseUrl);

        ctx.json(Map.of("ok", true, "nodeId", nodeConfig.nodeId));
    }

    private String requestPath(Context ctx) {
        String qs = ctx.queryString();
        if (qs != null && !qs.isEmpty()) {
            return ctx.path() + "?" + qs;
        }
        return ctx.path();
    }

    private void shutdown() {
        if (shutdownComplete) return;
        shutdownComplete = true;
        logger.info("Shutting down HamReporter server...");
        if (federation != null) {
            federation.shutdown();
        }
        if (server != null) {
            server.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        logger.info("HamReporter server shutdown complete");
    }

    public void stop(int exitCode) {
        shutdown();
        System.exit(exitCode);
    }

    private void loadNodeIdentity() {
        KeyPair kp;
        if (Files.exists(configPath.resolve(nodeConfig.privateKeyPath))
                && Files.exists(configPath.resolve(nodeConfig.publicKeyPath))) {
            try {
                kp = CryptoService.loadKeyPair(configPath.resolve(nodeConfig.privateKeyPath).toString(),
                        configPath.resolve(nodeConfig.publicKeyPath).toString());
            } catch (Exception e) {
                logger.error("Failed to load existing key pair", e);
                stop(1);
                return;
            }
        } else {
            try {
                kp = CryptoService.generateKeyPair();
                CryptoService.saveKeyPair(kp, configPath.resolve(nodeConfig.privateKeyPath).toString(),
                        configPath.resolve(nodeConfig.publicKeyPath).toString());
            } catch (Exception e) {
                logger.error("Failed to generate and save new key pair", e);
                stop(1);
                return;
            }
        }

        nodeIdentity = new NodeIdentity(nodeConfig.nodeId, nodeConfig.callsign, nodeConfig.baseUrl, nodeConfig.role,
                kp.getPrivate(), kp.getPublic());
    }

    private void setupCommandLineOptions(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("c", "config", true, "Path to configuration file");
        options.addOption("d", "data", true, "Path to data directory");
        options.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("h")) {
            HelpFormatter formatter = HelpFormatter.builder().get();
            formatter.printHelp("hamreporter-server", null, options, null, true);
            stop(0);
        }

        if (cmd.hasOption("c")) {
            configPath = Path.of(cmd.getOptionValue("c"));
        } else {
            configPath = DataPathResolver.getConfigPath("hamreporterserver");
        }

        if (cmd.hasOption("d")) {
            dataPath = Path.of(cmd.getOptionValue("d"));
        } else {
            dataPath = DataPathResolver.getDataPath("hamreporterserver");
        }
    }

    private void ensureDirectories() {
        try {
            logger.info("Ensuring configuration directory exists at: {}", configPath);
            Files.createDirectories(configPath);
            logger.info("Ensuring data directory exists at: {}", dataPath);
            Files.createDirectories(dataPath);
        } catch (IOException e) {
            logger.error("Failed to create necessary directories", e);
            stop(1);
        }
    }

    private void ensureAndLoadConfig() {
        Path configFilePath = configPath.resolve("config.toml");
        if (!Files.exists(configFilePath)) {
            NodeConfig defaultConfig = new NodeConfig();
            try {
                ConfigManager.saveConfig(defaultConfig, configFilePath);
                logger.info("Default configuration file created at: {}", configFilePath);
            } catch (Exception e) {
                logger.error("Failed to create default configuration file", e);
                stop(1);
            }
        }

        try {
            nodeConfig = ConfigManager.loadConfig(configFilePath);
            logger.info("Configuration loaded from: {}", configFilePath);
        } catch (Exception e) {
            logger.error("Failed to load configuration file", e);
            stop(1);
        }

        try {
            nodeConfig.validate();
        } catch (IllegalArgumentException e) {
            logger.error("Invalid configuration: {}", e.getMessage());
            stop(1);
        }
    }
}
