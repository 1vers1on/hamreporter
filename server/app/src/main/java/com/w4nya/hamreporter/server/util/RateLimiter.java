package com.w4nya.hamreporter.server.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RateLimiter {
    private static final int MAX_BUCKETS = 100_000;
    private static final class Bucket {
        private final double capacity;
        private final double ratePerSecond;
        private double tokens;

        private volatile long lastRefillNanos;

        Bucket(double capacity, double ratePerSecond) {
            this.capacity = capacity;
            this.ratePerSecond = ratePerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(int n) {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillNanos) / 1e9;
            tokens = Math.min(capacity, tokens + elapsed * ratePerSecond);
            lastRefillNanos = now;

            if (tokens >= n) {
                tokens -= n;
                return true;
            }
            return false;
        }

        synchronized long lastRefillNanos() {
            return lastRefillNanos;
        }

        boolean isExpired(long now, long idleTimeoutNanos) {
            return (now - lastRefillNanos()) > idleTimeoutNanos;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int perCallsignPerHour;
    private final int perIpPerHour;
    private final int burst;
    private final long idleTimeoutNanos;

    public RateLimiter(int perCallsignPerHour, int perIpPerHour, int burst) {
        if (perCallsignPerHour < 0 || perIpPerHour < 0) {
            throw new IllegalArgumentException("Rate limits must be non-negative");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("Burst must be at least 1");
        }
        this.perCallsignPerHour = perCallsignPerHour;
        this.perIpPerHour = perIpPerHour;
        this.burst = burst;
        this.idleTimeoutNanos = TimeUnit.HOURS.toNanos(1);
    }

    public boolean allowCallsign(String callsign) {
        if (callsign == null || callsign.isBlank())
            return false;

        return buckets.computeIfAbsent("cs:" + callsign.trim().toUpperCase(),
                k -> new Bucket(burst, perCallsignPerHour / 3600.0)).tryConsume(1);
    }

    public boolean allowIp(String ip) {
        if (ip == null || ip.isBlank())
            return false;

        return buckets.computeIfAbsent("ip:" + ip.trim(),
                k -> new Bucket(burst * 5, perIpPerHour / 3600.0)).tryConsume(1);
    }

    public void cleanup() {
        long now = System.nanoTime();
        buckets.values().removeIf(bucket -> bucket.isExpired(now, idleTimeoutNanos));
        if (buckets.size() > MAX_BUCKETS) {
            buckets.values().stream()
                    .sorted((a, b) -> Long.compare(a.lastRefillNanos(), b.lastRefillNanos()))
                    .limit(buckets.size() - MAX_BUCKETS / 2)
                    .forEach(bucket -> buckets.values().remove(bucket));
        }
    }
}
