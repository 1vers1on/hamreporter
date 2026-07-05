# HamReporter Python Client

A Python client library for the HamReporter federation server.

## Installation

```bash
pip install hamreporter
```

Requires Python 3.10+.

## Quick Start

```python
from hamreporter import Ed25519KeyPair, HamReporterClient, SignalReport
import time

# Generate or load your key pair
key_pair = Ed25519KeyPair()
# Or load from file: key_pair = Ed25519KeyPair.from_private_key_file("private.key")

# Create a client
with HamReporterClient(
    base_url="http://localhost:45271",
    key_pair=key_pair,
    callsign="W1AW",
) as client:

    # Check server health
    health = client.health()
    print(health)  # {"status": "ok", "nodeId": "default-node"}

    # Submit a signal report
    report = SignalReport(
        reporter_callsign="W1AW",
        reporter_grid="FN31pr",
        heard_callsign="K1ABC",
        heard_grid="FM18lv",
        frequency_hz=14074000,
        mode="FT8",
        snr_db=-10.0,
        reported_at_epoch_ms=int(time.time() * 1000),
    )
    result = client.submit_report(report)
    print(f"Report hash: {result.hash}")

    # Query reports
    reports = client.query_reports(reporter="W1AW", limit=10)

    # Get a specific report
    report = client.get_report(result.hash)

    # Send heartbeat
    client.heartbeat()

    # List known peers
    peers = client.peers()

    # Federation: sync from another node
    origins = client.federation_origins()
    for origin, max_seq in origins.items():
        reports = client.federation_since(origin, since=0, limit=100)
```

## API

### `HamReporterClient(base_url, key_pair, callsign, node_id=None, timeout=15.0)`

Creates a new client instance.

#### Public Methods

| Method | Description |
|--------|-------------|
| `health()` | Health check, returns `{"status", "nodeId"}` |
| `node_info()` | Get server node info as `PeerInfo` |
| `peers()` | List all known federation peers |
| `active_callsigns()` | List recently active callsigns |
| `get_report(hash)` | Get a report by hash, or `None` |
| `query_reports(**filters)` | Query reports with optional filters |
| `submit_report(report)` | Submit and sign a new report |
| `heartbeat()` | Send a heartbeat |
| `federation_origins()` | Get origin server sequence map |
| `federation_since(origin, since, limit)` | Fetch reports since a sequence number |
| `federation_hello(node_id, base_url, role)` | Introduce your node to a peer |
| `federation_announce(report)` | Push a report to this peer |

### `Ed25519KeyPair(private_key=None)`

Ed25519 key pair for signing.

| Method | Description |
|--------|-------------|
| `Ed25519KeyPair()` | Generate a new key pair |
| `from_private_key_b64(b64)` | Load from base64 private key |
| `from_private_key_file(path)` | Load from file |
| `sign(data)` | Sign a string, returns base64 signature |
| `public_key_b64` | Base64-encoded public key |

### `SignalReport(**kwargs)`

Signal report data model with canonical serialization matching the server protocol.

## Running Tests

```bash
pip install -e ".[dev]"
pytest
```
