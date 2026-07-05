from hamreporter.crypto import Ed25519KeyPair, canonical_http_signature, sha256_hex, verify_signature


def test_key_pair_generation():
    kp = Ed25519KeyPair()
    assert kp.public_key_b64 is not None
    assert len(kp.public_key_bytes) == 32
    assert len(kp.private_key_bytes) == 32


def test_key_pair_sign_verify():
    kp = Ed25519KeyPair()
    data = "hello world"
    sig = kp.sign(data)
    assert sig is not None
    assert verify_signature(kp.public_key_b64, data, sig)


def test_key_pair_sign_verify_wrong_data():
    kp = Ed25519KeyPair()
    sig = kp.sign("original")
    assert not verify_signature(kp.public_key_b64, "tampered", sig)


def test_key_pair_sign_verify_wrong_key():
    kp1 = Ed25519KeyPair()
    kp2 = Ed25519KeyPair()
    sig = kp1.sign("data")
    assert not verify_signature(kp2.public_key_b64, "data", sig)


def test_key_pair_from_private_key_b64():
    kp1 = Ed25519KeyPair()
    b64 = base64.b64encode(kp1.private_key_bytes).decode()
    kp2 = Ed25519KeyPair.from_private_key_b64(b64)
    assert kp1.public_key_b64 == kp2.public_key_b64


def test_sha256_hex_consistent():
    h1 = sha256_hex("test")
    h2 = sha256_hex("test")
    assert h1 == h2
    assert len(h1) == 64


def test_sha256_hex_differs():
    assert sha256_hex("foo") != sha256_hex("bar")


def test_canonical_http_signature_format():
    c = canonical_http_signature("POST", "/api/test", 12345, "nonce1", b"body")
    lines = c.split("\n")
    assert lines[0] == "POST"
    assert lines[1] == "/api/test"
    assert lines[2] == "12345"
    assert lines[3] == "nonce1"
    assert len(lines[4]) == 64


def test_canonical_http_signature_null_body():
    c1 = canonical_http_signature("GET", "/path", 1, "n", None)
    c2 = canonical_http_signature("GET", "/path", 1, "n", b"")
    assert c1 == c2


import base64
