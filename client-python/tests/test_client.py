from hamreporter.client import HamReporterClient
from hamreporter.crypto import Ed25519KeyPair
from hamreporter.models import SignalReport


def test_client_submit_report_hashes_and_signs():
    kp = Ed25519KeyPair()
    client = HamReporterClient(base_url="http://localhost:45271", key_pair=kp, callsign="W1AW")

    report = SignalReport(
        reporter_callsign="W1AW",
        reporter_grid="FN31",
        heard_callsign="K1ABC",
        heard_grid="FM18",
        frequency_hz=14074000,
        mode="FT8",
        snr_db=-10.0,
        reported_at_epoch_ms=1000,
    )

    report.reporter_public_key = kp.public_key_b64
    canonical = report.canonical_reporter_payload()

    from hamreporter.crypto import sha256_hex

    expected_hash = sha256_hex(canonical)
    expected_sig = kp.sign(canonical)

    report.hash = expected_hash
    report.signature = expected_sig

    assert report.hash == expected_hash
    assert report.signature == expected_sig
    assert report.reporter_callsign == "W1AW"

    client.close()


def test_client_rejects_wrong_callsign():
    kp = Ed25519KeyPair()
    client = HamReporterClient(base_url="http://localhost:45271", key_pair=kp, callsign="W1AW")

    report = SignalReport(
        reporter_callsign="K9XYZ",
        heard_callsign="W1AW",
        frequency_hz=14074000,
        reported_at_epoch_ms=1000,
    )

    try:
        client.submit_report(report)
        assert False, "Should have raised ValueError"
    except ValueError:
        pass

    client.close()


def test_client_context_manager():
    kp = Ed25519KeyPair()
    with HamReporterClient(base_url="http://localhost:45271", key_pair=kp, callsign="W1AW") as client:
        assert client.callsign == "W1AW"
