package com.w4nya.hamreporter.server;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Objects;

import com.w4nya.hamreporter.server.util.CryptoService;

public class NodeIdentity {
    private final String nodeId;
    public final String callsign;
    public final String baseUrl;
    public final NodeRole role;
    private final PrivateKey privateKey;
    public final PublicKey publicKey;
    public final String publicKeyB64;

    public NodeIdentity(String nodeId, String callsign, String baseUrl, NodeRole role,
                        PrivateKey privateKey, PublicKey publicKey) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.callsign = callsign;
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey must not be null");
        this.publicKeyB64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public String nodeId() {
        return nodeId;
    }

    public String sign(String data) {
        return CryptoService.signBase64(privateKey, data);
    }
}
