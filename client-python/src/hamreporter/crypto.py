from __future__ import annotations

import base64
import hashlib
from typing import Optional

from nacl.signing import SigningKey, VerifyKey
from nacl.exceptions import BadSignatureError


class Ed25519KeyPair:
    def __init__(self, private_key: Optional[bytes] = None):
        if private_key is not None:
            self._signing_key = SigningKey(private_key)
        else:
            self._signing_key = SigningKey.generate()
        self._verify_key = self._signing_key.verify_key

    @property
    def private_key_bytes(self) -> bytes:
        return bytes(self._signing_key)

    @property
    def public_key_bytes(self) -> bytes:
        return bytes(self._verify_key)

    @property
    def public_key_b64(self) -> str:
        return base64.b64encode(self.public_key_bytes).decode("ascii")

    def sign(self, data: str) -> str:
        signed = self._signing_key.sign(data.encode("utf-8"))
        return base64.b64encode(signed.signature).decode("ascii")

    @classmethod
    def from_private_key_b64(cls, b64: str) -> Ed25519KeyPair:
        raw = base64.b64decode(b64)
        return cls(private_key=raw)

    @classmethod
    def from_private_key_file(cls, path: str) -> Ed25519KeyPair:
        with open(path, "r") as f:
            b64 = f.read().strip()
        return cls.from_private_key_b64(b64)


def sha256_hex(data: str) -> str:
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def verify_signature(public_key_b64: str, data: str, signature_b64: str) -> bool:
    try:
        pub_bytes = base64.b64decode(public_key_b64)
        sig_bytes = base64.b64decode(signature_b64)
        vk = VerifyKey(pub_bytes)
        vk.verify(data.encode("utf-8"), sig_bytes)
        return True
    except (BadSignatureError, Exception):
        return False


def canonical_http_signature(method: str, path: str, timestamp: int, nonce: str, body: Optional[bytes]) -> str:
    body = body or b""
    body_hash = hashlib.sha256(body).hexdigest()
    return f"{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{body_hash}"
