package com.w4nya.hamreporter.server.util;

import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.w4nya.hamreporter.server.NodeRole;
import com.w4nya.hamreporter.server.PeerInfo;
import com.w4nya.hamreporter.server.SignalReport;
import com.w4nya.hamreporter.server.SignedRequestContext;
import com.w4nya.hamreporter.server.storage.Storage;

public class CryptoVerifier {
    private static final Logger logger = LoggerFactory.getLogger(CryptoVerifier.class);
    private final Storage storage;
    private final ConcurrentHashMap<String, CachedKey> peerKeyCache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MS = 300_000;

    private static final class CachedKey {
        final PublicKey key;
        final long cachedAt;

        CachedKey(PublicKey key) {
            this.key = key;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - cachedAt) > CACHE_TTL_MS;
        }
    }

    public CryptoVerifier(Storage storage) {
        this.storage = storage;
    }

    public boolean verifyReport(SignalReport r) {
        if (r.hash() == null || r.signature() == null)
            return false;
        String recomputedHash = CryptoService.sha256Hex(r.canonicalReporterPayload());
        if (!recomputedHash.equals(r.hash()))
            return false;

        try {
            PublicKey reporterKey = CryptoService.publicKeyFromBase64(r.reporterPublicKey());
            if (!CryptoService.verifyBase64(reporterKey, r.canonicalReporterPayload(), r.signature()))
                return false;
        } catch (Exception e) {
            return false;
        }

        if (r.isOriginated()) {
            PublicKey originKey = getPeerKey(r.originServer());
            if (originKey == null)
                return false;
            try {
                if (!CryptoService.verifyBase64(originKey, r.canonicalOriginPayload(), r.originSignature()))
                    return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public boolean verifyReportLenient(SignalReport r) {
        if (r.hash() == null || r.signature() == null)
            return false;
        String recomputedHash = CryptoService.sha256Hex(r.canonicalReporterPayload());
        if (!recomputedHash.equals(r.hash()))
            return false;

        try {
            PublicKey reporterKey = CryptoService.publicKeyFromBase64(r.reporterPublicKey());
            if (!CryptoService.verifyBase64(reporterKey, r.canonicalReporterPayload(), r.signature()))
                return false;
        } catch (Exception e) {
            return false;
        }

        if (r.isOriginated()) {
            PublicKey originKey = getPeerKey(r.originServer());
            if (originKey != null) {
                try {
                    if (!CryptoService.verifyBase64(originKey, r.canonicalOriginPayload(), r.originSignature()))
                        return false;
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean verifyHttpSignature(SignedRequestContext ctx, String method, String path) {
        try {
            PublicKey pk = CryptoService.publicKeyFromBase64(ctx.publicKeyB64());
            String canon = HttpSignature.canonical(method, path, ctx.timestamp(), ctx.nonce(), ctx.body());
            return CryptoService.verifyBase64(pk, canon, ctx.signature());
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid public key in HTTP signature: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.debug("HTTP signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private PublicKey getPeerKey(String originServer) {
        CachedKey cached = peerKeyCache.get(originServer);
        if (cached != null && !cached.isExpired()) {
            return cached.key;
        }
        try {
            PeerInfo originPeer = storage.getPeer(originServer);
            if (originPeer == null) return null;
            PublicKey key = CryptoService.publicKeyFromBase64(originPeer.publicKeyB64());
            peerKeyCache.put(originServer, new CachedKey(key));
            return key;
        } catch (Exception e) {
            logger.debug("Failed to load peer key for {}: {}", originServer, e.getMessage());
            return null;
        }
    }

    public void invalidatePeerKey(String nodeId) {
        peerKeyCache.remove(nodeId);
    }
}
