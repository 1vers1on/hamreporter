package com.w4nya.hamreporter.server;

public record SignedRequestContext(
        String callsign,
        String publicKeyB64,
        long timestamp,
        String nonce,
        String signature,
        byte[] body) {

    public SignedRequestContext {
        body = body == null ? new byte[0] : body.clone();
    }
}
