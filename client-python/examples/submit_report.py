"""Example usage of the HamReporter Python client."""

import time

from hamreporter import Ed25519KeyPair, HamReporterClient, SignalReport


def main():
    key_pair = Ed25519KeyPair()
    client = HamReporterClient(
        base_url="http://localhost:45271",
        key_pair=key_pair,
        callsign="W1AW",
    )

    # Health check
    health = client.health()
    print(f"Server health: {health}")

    # Get node info
    info = client.node_info()
    print(f"Node info: {info}")

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
    print(f"Submitted report: {result}")
    print(f"  Hash: {result.hash}")
    print(f"  Origin: {result.origin_server} seq={result.origin_seq}")

    # Query reports
    reports = client.query_reports(reporter="W1AW", limit=10)
    print(f"Found {len(reports)} reports from W1AW")

    # Get a specific report
    if result.hash:
        fetched = client.get_report(result.hash)
        print(f"Fetched report: {fetched}")

    # Send heartbeat
    hb = client.heartbeat()
    print(f"Heartbeat: {hb}")

    # List peers
    peers = client.peers()
    print(f"Known peers: {peers}")

    # List active callsigns
    callsigns = client.active_callsigns()
    print(f"Active callsigns: {len(callsigns)}")

    client.close()


if __name__ == "__main__":
    main()
