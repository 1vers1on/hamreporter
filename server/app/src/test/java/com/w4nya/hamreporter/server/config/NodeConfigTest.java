package com.w4nya.hamreporter.server.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

public class NodeConfigTest {

    @Test
    void defaultNodeId() {
        NodeConfig c = new NodeConfig();
        assertEquals("default-node", c.nodeId);
    }

    @Test
    void defaultPort() {
        NodeConfig c = new NodeConfig();
        assertEquals(45271, c.port);
    }

    @Test
    void defaultRole() {
        NodeConfig c = new NodeConfig();
        assertEquals(com.w4nya.hamreporter.server.NodeRole.ROLLING, c.role);
    }

    @Test
    void retentionDurationParsesDays() {
        NodeConfig c = new NodeConfig();
        c.retention = "7d";
        assertEquals(Duration.ofDays(7), c.retentionDuration());
    }

    @Test
    void retentionDurationParsesHours() {
        NodeConfig c = new NodeConfig();
        c.retention = "24h";
        assertEquals(Duration.ofHours(24), c.retentionDuration());
    }

    @Test
    void retentionDurationParsesMinutes() {
        NodeConfig c = new NodeConfig();
        c.retention = "30m";
        assertEquals(Duration.ofMinutes(30), c.retentionDuration());
    }

    @Test
    void retentionDurationParsesSeconds() {
        NodeConfig c = new NodeConfig();
        c.retention = "90s";
        assertEquals(Duration.ofSeconds(90), c.retentionDuration());
    }

    @Test
    void retentionDurationParsesIso() {
        NodeConfig c = new NodeConfig();
        c.retention = "PT48H";
        assertEquals(Duration.ofHours(48), c.retentionDuration());
    }

    @Test
    void callsignTtlDuration() {
        NodeConfig c = new NodeConfig();
        c.callsignTtl = "15m";
        assertEquals(Duration.ofMinutes(15), c.callsignTtlDuration());
    }

    @Test
    void syncIntervalDuration() {
        NodeConfig c = new NodeConfig();
        c.syncInterval = "30s";
        assertEquals(Duration.ofSeconds(30), c.syncIntervalDuration());
    }

    @Test
    void gossipIntervalDuration() {
        NodeConfig c = new NodeConfig();
        c.gossipInterval = "5s";
        assertEquals(Duration.ofSeconds(5), c.gossipIntervalDuration());
    }

    @Test
    void defaultPeersList() {
        NodeConfig c = new NodeConfig();
        assertNotNull(c.peers);
        assertTrue(c.peers.isEmpty());
    }

    @Test
    void defaultTrustedPeerKeys() {
        NodeConfig c = new NodeConfig();
        assertNotNull(c.trustedPeerKeys);
        assertTrue(c.trustedPeerKeys.isEmpty());
    }
}
