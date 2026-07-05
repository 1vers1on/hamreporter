package com.w4nya.hamreporter.server;

import java.util.Objects;

public record PeerInfo(
        String nodeId,
        String callsign,
        String baseUrl,
        String publicKeyB64,
        NodeRole role,
        long lastSeen,
        boolean trusted) {

    public PeerInfo {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    }
}
