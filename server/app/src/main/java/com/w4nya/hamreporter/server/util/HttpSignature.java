package com.w4nya.hamreporter.server.util;

import java.security.MessageDigest;
import java.util.Objects;

public final class HttpSignature {
    private HttpSignature() {}

    public static String canonical(String method, String path, long timestamp, String nonce, byte[] body) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(nonce, "nonce must not be null");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(body == null ? new byte[0] : body);
            StringBuilder hex = new StringBuilder(h.length * 2);
            String d = "0123456789abcdef";
            for (byte b : h) { hex.append(d.charAt((b>>4)&0xF)).append(d.charAt(b&0xF)); }
            return method.toUpperCase() + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
