package com.w4nya.hamreporter.server;

import java.time.Duration;
import java.util.List;

import com.w4nya.hamreporter.server.storage.Storage;

public class CallsignRegistry {
    private final Storage storage;
    private final Duration ttl;

    public record Entry(String callsign, String publicKeyB64, long lastSeenMs,
            String originServer, Long lastReportSeq) {
    }

    public CallsignRegistry(Storage storage, Duration ttl) {
        this.storage = storage;
        this.ttl = ttl;
    }

    public void touch(String callsign, String publicKey, String originServer, Long lastSeq) {
        storage.touchCallsign(callsign, publicKey, originServer, lastSeq);
    }

    public List<Entry> active() {
        long since = System.currentTimeMillis() - ttl.toMillis();
        return storage.activeCallsigns(since);
    }

    public boolean isActive(String callsign) {
        return storage.activeCallsigns(System.currentTimeMillis() - ttl.toMillis()).stream()
                .anyMatch(e -> e.callsign.equalsIgnoreCase(callsign));
    }

    public String getPublicKey(String callsign) {
        return storage.getCallsignKey(callsign);
    }
}
