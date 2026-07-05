from hamreporter.models import NodeRole, PeerInfo, SignalReport


def test_signal_report_callsign_uppercase():
    r = SignalReport(reporter_callsign="w1aw", heard_callsign="k1abc")
    assert r.reporter_callsign == "W1AW"
    assert r.heard_callsign == "K1ABC"


def test_signal_report_mode_uppercase():
    r = SignalReport(mode="ft8")
    assert r.mode == "FT8"


def test_signal_report_canonical_reporter_payload():
    r = SignalReport(
        reporter_callsign="W1AW",
        reporter_grid="FN31",
        reporter_public_key="cHVi",
        heard_callsign="K1ABC",
        heard_grid="FM18",
        frequency_hz=14074000,
        mode="FT8",
        snr_db=-10.0,
        reported_at_epoch_ms=1000,
    )
    canon = r.canonical_reporter_payload()
    assert "reporterCallsign=W1AW" in canon
    assert "heardCallsign=K1ABC" in canon
    assert "frequencyHz=14074000" in canon
    assert "mode=FT8" in canon
    assert "snrDb=-10.0" in canon


def test_signal_report_canonical_reporter_payload_null_snr():
    r = SignalReport(
        reporter_callsign="W1AW",
        reporter_public_key="cHVi",
        heard_callsign="K1ABC",
        frequency_hz=14074000,
        mode="FT8",
        snr_db=None,
        reported_at_epoch_ms=1000,
    )
    canon = r.canonical_reporter_payload()
    assert "snrDb=null" in canon


def test_signal_report_canonical_origin_payload():
    r = SignalReport(
        hash="abc123",
        origin_server="node1",
        origin_seq=42,
        received_at_epoch_ms=2000,
    )
    assert r.canonical_origin_payload() == "node1|42|abc123|2000"


def test_signal_report_is_originated():
    r1 = SignalReport(origin_server="node1", origin_signature="sig")
    assert r1.is_originated

    r2 = SignalReport(origin_server="node1", origin_signature=None)
    assert not r2.is_originated

    r3 = SignalReport(origin_server="node1", origin_signature="   ")
    assert not r3.is_originated


def test_signal_report_to_dict():
    r = SignalReport(
        hash="abc",
        reporter_callsign="W1AW",
        reporter_public_key="cHVi",
        heard_callsign="K1ABC",
        frequency_hz=14074000,
        reported_at_epoch_ms=1000,
        mode="FT8",
        snr_db=-10.0,
    )
    d = r.to_dict()
    assert d["hash"] == "abc"
    assert d["reporterCallsign"] == "W1AW"
    assert d["frequencyHz"] == 14074000
    assert d["mode"] == "FT8"
    assert d["snrDb"] == -10.0


def test_signal_report_to_dict_excludes_nulls():
    r = SignalReport(
        hash="abc",
        reporter_callsign="W1AW",
        reporter_public_key="cHVi",
        heard_callsign="K1ABC",
        frequency_hz=14074000,
        reported_at_epoch_ms=1000,
    )
    d = r.to_dict()
    assert "reporterGrid" not in d
    assert "heardGrid" not in d
    assert "snrDb" not in d


def test_signal_report_from_dict():
    data = {
        "hash": "abc",
        "originServer": "node1",
        "originSeq": 5,
        "reporterCallsign": "W1AW",
        "reporterGrid": "FN31",
        "reporterPublicKey": "cHVi",
        "heardCallsign": "K1ABC",
        "heardGrid": "FM18",
        "frequencyHz": 14074000,
        "mode": "FT8",
        "snrDb": -10.0,
        "reportedAtEpochMs": 1000,
        "receivedAtEpochMs": 2000,
        "extra": {"key": "val"},
        "signature": "sig",
        "originSignature": "osig",
    }
    r = SignalReport.from_dict(data)
    assert r.hash == "abc"
    assert r.origin_server == "node1"
    assert r.origin_seq == 5
    assert r.reporter_callsign == "W1AW"
    assert r.heard_callsign == "K1ABC"
    assert r.snr_db == -10.0
    assert r.extra == {"key": "val"}


def test_signal_report_round_trip():
    r = SignalReport(
        hash="abc",
        reporter_callsign="W1AW",
        reporter_grid="FN31",
        reporter_public_key="cHVi",
        heard_callsign="K1ABC",
        heard_grid="FM18",
        frequency_hz=14074000,
        mode="FT8",
        snr_db=-10.0,
        reported_at_epoch_ms=1000,
        signature="sig",
    )
    d = r.to_dict()
    r2 = SignalReport.from_dict(d)
    assert r2.hash == r.hash
    assert r2.reporter_callsign == r.reporter_callsign
    assert r2.heard_callsign == r.heard_callsign
    assert r2.frequency_hz == r.frequency_hz
    assert r2.mode == r.mode
    assert r2.snr_db == r.snr_db


def test_peer_info_from_dict():
    data = {
        "nodeId": "node1",
        "callsign": "W1AW",
        "baseUrl": "http://localhost:8080",
        "publicKeyB64": "cHVi",
        "role": "ROLLING",
        "lastSeen": 1000,
        "trusted": True,
    }
    p = PeerInfo.from_dict(data)
    assert p.node_id == "node1"
    assert p.callsign == "W1AW"
    assert p.role == NodeRole.ROLLING
    assert p.trusted is True


def test_peer_info_from_dict_invalid_role():
    data = {
        "nodeId": "node1",
        "role": "INVALID",
    }
    p = PeerInfo.from_dict(data)
    assert p.role == NodeRole.ROLLING
