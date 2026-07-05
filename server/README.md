# HamReporter Server

A federated amateur radio signal report aggregation server. Nodes share signal reports via a gossip/sync protocol with Ed25519 cryptographic verification.

## Quick Start

```bash
./gradlew run
```

The server starts on port 45271 by default. Configuration is created at `~/.config/hamreporterserver/config.toml` and data at `~/.local/share/hamreporterserver/`.

## Configuration

Configuration is loaded from a TOML file. Default values:

```toml
nodeId = "default-node"
callsign = "NOCALL"
bindHost = "0.0.0.0"
port = 45271
baseUrl = "http://localhost:45271"
role = "ROLLING"              # ROLLING or ARCHIVE
retention = "7d"              # How long to keep reports (ROLLING only)
privateKeyPath = "private.key"
publicKeyPath = "public.key"

perCallsignReportsPerHour = 60
perIpReportsPerHour = 1200
burstReports = 10
callsignTtl = "15m"
syncInterval = "30s"
gossipInterval = "5s"
syncBatchSize = 500
maxRequestBodyBytes = 262144
maxClockSkewSeconds = 300
trustForwardedHeaders = false  # Set true behind a reverse proxy

# Federation peers
# peers = ["http://peer1:45271", "http://peer2:45271"]

# Trusted peer public keys (baseUrl -> base64 Ed25519 public key)
# [trustedPeerKeys]
# "http://peer1:45271" = "MCowBQYDK2VwAyEA..."
```

Duration format: `7d` = 7 days, `30s` = 30 seconds, `15m` = 15 minutes, `24h` = 24 hours, or ISO-8601 (`PT48H`).

### Node Roles

- **ROLLING**: Enforces retention policy, deletes old reports after the configured retention period.
- **ARCHIVE**: Keeps all reports indefinitely, never deletes.

### Command Line Options

```
Usage: hamreporter-server
  -c, --config <path>   Path to configuration directory
  -d, --data <path>     Path to data directory
  -h, --help            Show help
```

## Cryptography

The server uses **Ed25519** for all signatures. On first run, a key pair is generated and stored in the config directory. The private key file is set owner-readable only.

### Signature Flow

1. Reporter signs the canonical reporter payload with their Ed25519 private key.
2. Originating server signs the canonical origin payload with its node private key.
3. All HTTP requests include signature headers for authentication.

### Canonical Reporter Payload

```
reporterCallsign=<CALLSIGN>
reporterGrid=<GRID>
reporterPublicKey=<BASE64_KEY>
heardCallsign=<CALLSIGN>
heardGrid=<GRID>
frequencyHz=<FREQ>
mode=<MODE>
snrDb=<SNR_OR_null>
reportedAtEpochMs=<TIMESTAMP>
extra={<KEY=VALUE,...>}
```

### Canonical Origin Payload

```
<originServer>|<originSeq>|<hash>|<receivedAtEpochMs>
```

### HTTP Signature

The canonical form for HTTP signatures is:

```
<METHOD>\n<path>\n<timestamp>\n<nonce>\n<sha256_hex_of_body>
```

Signed with the reporter's Ed25519 private key, sent as Base64 in the `X-Signature` header.

## API Reference

### Public Endpoints

#### `GET /api/v1/health`

Health check.

**Response** `200`:
```json
{"status": "ok", "nodeId": "default-node"}
```

#### `GET /api/v1/nodes/info`

Get this node's identity information.

**Response** `200`:
```json
{
  "nodeId": "default-node",
  "callsign": "W1AW",
  "baseUrl": "http://localhost:45271",
  "publicKeyB64": "MCowBQYDK2VwAyEA...",
  "role": "ROLLING",
  "lastSeen": 1700000000000,
  "trusted": true
}
```

#### `GET /api/v1/nodes/peers`

List all known federation peers.

**Response** `200`: Array of `PeerInfo` objects.

#### `GET /api/v1/callsigns/active`

List recently active callsigns.

**Response** `200`: Array of callsign registry entries with `callsign`, `publicKeyB64`, `lastSeenMs`, `originServer`, `lastReportSeq`.

#### `GET /api/v1/reports`

Query signal reports.

**Query Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `reporter` | string | Filter by reporter callsign |
| `heard` | string | Filter by heard callsign |
| `freqMin` | long | Minimum frequency (Hz) |
| `freqMax` | long | Maximum frequency (Hz) |
| `mode` | string | Filter by mode (FT8, CW, etc.) |
| `reporterGrid` | string | Reporter grid prefix (e.g., "FN") |
| `heardGrid` | string | Heard grid prefix (e.g., "FM") |
| `since` | long | Minimum received timestamp (epoch ms) |
| `until` | long | Maximum received timestamp (epoch ms) |
| `limit` | int | Max results (1-1000, default 200) |
| `sort` | string | "asc" or "desc" (default "desc") |

**Response** `200`: Array of `SignalReport` objects.

#### `GET /api/v1/reports/{hash}`

Get a specific report by its SHA-256 hash.

**Response** `200`: `SignalReport` object. `404` if not found. `400` for invalid hash format.

### Authenticated Endpoints

All authenticated endpoints require these headers:

| Header | Description |
|--------|-------------|
| `X-Callsign` | Your callsign (uppercase) |
| `X-Public-Key` | Your Ed25519 public key (Base64) |
| `X-Timestamp` | Current epoch milliseconds |
| `X-Nonce` | Unique nonce (UUID recommended) |
| `X-Signature` | Ed25519 signature of the canonical HTTP string (Base64) |

#### `POST /api/v1/reports`

Submit a new signal report.

**Request Body**: `SignalReport` JSON (without `originServer`, `originSeq`, `receivedAtEpochMs`, `originSignature` — these are set by the server).

**Response** `201`: The originated report with server-assigned fields. `200` if duplicate. `409` on callsign key conflict. `429` on rate limit.

#### `POST /api/v1/heartbeat`

Send a heartbeat to keep your callsign active.

**Response** `200`:
```json
{"ok": true, "serverTime": 1700000000000}
```

`409` on callsign key conflict.

### Federation Endpoints

Federation endpoints require the same authentication headers plus `X-Node-Id`.

#### `POST /api/v1/federation/announce`

Announce a signal report to this node from a federated peer.

#### `GET /api/v1/federation/origins`

Get the map of origin servers to their maximum sequence numbers.

**Response** `200`: `{"originServer1": 42, "originServer2": 15}`

#### `GET /api/v1/federation/since?origin=<server>&since=<seq>&limit=<n>`

Fetch reports from a specific origin server since a given sequence number.

**Query Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `origin` | string | Yes | Origin server ID |
| `since` | long | Yes | Sequence number to start after |
| `limit` | int | No | Max results (1-1000, default 500) |

#### `POST /api/v1/federation/hello`

Introduce your node to this peer.

**Request Body**:
```json
{
  "nodeId": "my-node",
  "callsign": "W1AW",
  "baseUrl": "http://my-node:45271",
  "publicKey": "MCowBQYDK2VwAyEA...",
  "role": "ROLLING"
}
```

## SignalReport Format

```json
{
  "hash": "sha256-of-canonical-reporter-payload",
  "originServer": "node-id-of-originating-server",
  "originSeq": 1,
  "reporterCallsign": "W1AW",
  "reporterGrid": "FN31pr",
  "reporterPublicKey": "MCowBQYDK2VwAyEA...",
  "heardCallsign": "K1ABC",
  "heardGrid": "FM18lv",
  "frequencyHz": 14074000,
  "mode": "FT8",
  "snrDb": -10.0,
  "reportedAtEpochMs": 1700000000000,
  "receivedAtEpochMs": 1700000000100,
  "extra": {},
  "signature": "base64-ed25519-signature-of-canonical-reporter-payload",
  "originSignature": "base64-ed25519-signature-of-canonical-origin-payload"
}
```

## Federation Protocol

1. **Hello**: Node A sends GET `/api/v1/nodes/info` to Node B (unauthenticated), then POST `/api/v1/federation/hello` with its identity.
2. **Gossip**: When Node A originates a report, it pushes it to all known peers via POST `/api/v1/federation/announce`.
3. **Sync**: Periodically, nodes pull missed reports from peers via GET `/api/v1/federation/origins` and GET `/api/v1/federation/since`.

## Rate Limiting

- **Per-IP**: 1200 reports/hour with burst of 50
- **Per-Callsign**: 60 reports/hour with burst of 10

Rate-limited requests receive `429` with `{"error": "ip rate limit"}` or `{"error": "callsign rate limit"}`.

## Data Storage

All data is stored in SQLite at `<data-dir>/hamreporter.db` using WAL mode for concurrent read/write performance.

### Tables

| Table | Purpose |
|-------|---------|
| `signal_reports` | Stored signal reports |
| `callsign_registry` | Callsign-to-public-key mapping with last-seen tracking |
| `peer_directory` | Known federation peers |
| `peer_watermarks` | Sync progress tracking per peer per origin |
| `seq_counters` | Per-origin-server sequence number counters |
| `audit_log` | Audit trail of significant events |
| `seen_nonces` | Replay attack prevention |
