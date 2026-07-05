from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional


class NodeRole(str, Enum):
    ROLLING = "ROLLING"
    ARCHIVE = "ARCHIVE"


class SignalReport:
    __slots__ = (
        "hash",
        "origin_server",
        "origin_seq",
        "reporter_callsign",
        "reporter_grid",
        "reporter_public_key",
        "heard_callsign",
        "heard_grid",
        "frequency_hz",
        "mode",
        "snr_db",
        "reported_at_epoch_ms",
        "received_at_epoch_ms",
        "extra",
        "signature",
        "origin_signature",
    )

    def __init__(
        self,
        *,
        hash: Optional[str] = None,
        origin_server: Optional[str] = None,
        origin_seq: int = 0,
        reporter_callsign: str = "",
        reporter_grid: Optional[str] = None,
        reporter_public_key: str = "",
        heard_callsign: str = "",
        heard_grid: Optional[str] = None,
        frequency_hz: int = 0,
        mode: Optional[str] = None,
        snr_db: Optional[float] = None,
        reported_at_epoch_ms: int = 0,
        received_at_epoch_ms: int = 0,
        extra: Optional[Dict[str, str]] = None,
        signature: Optional[str] = None,
        origin_signature: Optional[str] = None,
    ):
        self.hash = hash
        self.origin_server = origin_server
        self.origin_seq = origin_seq
        self.reporter_callsign = reporter_callsign.upper().strip() if reporter_callsign else ""
        self.reporter_grid = reporter_grid
        self.reporter_public_key = reporter_public_key
        self.heard_callsign = heard_callsign.upper().strip() if heard_callsign else ""
        self.heard_grid = heard_grid
        self.frequency_hz = frequency_hz
        self.mode = mode.upper().strip() if mode else None
        self.snr_db = snr_db
        self.reported_at_epoch_ms = reported_at_epoch_ms
        self.received_at_epoch_ms = received_at_epoch_ms
        self.extra = extra or {}
        self.signature = signature
        self.origin_signature = origin_signature

    @property
    def is_originated(self) -> bool:
        return bool(self.origin_server and self.origin_signature and self.origin_signature.strip())

    def canonical_reporter_payload(self) -> str:
        snr_str = "null" if self.snr_db is None else str(self.snr_db)
        extra_str = "{}" if not self.extra else (
            "{" + ",".join(f"{k}={v}" for k, v in sorted(self.extra.items())) + "}"
        )
        lines = [
            f"reporterCallsign={self.reporter_callsign}",
            f"reporterGrid={self.reporter_grid}",
            f"reporterPublicKey={self.reporter_public_key}",
            f"heardCallsign={self.heard_callsign}",
            f"heardGrid={self.heard_grid}",
            f"frequencyHz={self.frequency_hz}",
            f"mode={self.mode}",
            f"snrDb={snr_str}",
            f"reportedAtEpochMs={self.reported_at_epoch_ms}",
            f"extra={extra_str}",
        ]
        return "\n".join(lines)

    def canonical_origin_payload(self) -> str:
        return f"{self.origin_server}|{self.origin_seq}|{self.hash}|{self.received_at_epoch_ms}"

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "hash": self.hash,
            "reporterCallsign": self.reporter_callsign,
            "reporterPublicKey": self.reporter_public_key,
            "heardCallsign": self.heard_callsign,
            "frequencyHz": self.frequency_hz,
            "reportedAtEpochMs": self.reported_at_epoch_ms,
        }
        if self.origin_server is not None:
            d["originServer"] = self.origin_server
        if self.origin_seq:
            d["originSeq"] = self.origin_seq
        if self.reporter_grid is not None:
            d["reporterGrid"] = self.reporter_grid
        if self.heard_grid is not None:
            d["heardGrid"] = self.heard_grid
        if self.mode is not None:
            d["mode"] = self.mode
        if self.snr_db is not None:
            d["snrDb"] = self.snr_db
        if self.received_at_epoch_ms:
            d["receivedAtEpochMs"] = self.received_at_epoch_ms
        if self.extra:
            d["extra"] = self.extra
        if self.signature is not None:
            d["signature"] = self.signature
        if self.origin_signature is not None:
            d["originSignature"] = self.origin_signature
        return d

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> SignalReport:
        return cls(
            hash=data.get("hash"),
            origin_server=data.get("originServer"),
            origin_seq=data.get("originSeq", 0),
            reporter_callsign=data.get("reporterCallsign", ""),
            reporter_grid=data.get("reporterGrid"),
            reporter_public_key=data.get("reporterPublicKey", ""),
            heard_callsign=data.get("heardCallsign", ""),
            heard_grid=data.get("heardGrid"),
            frequency_hz=data.get("frequencyHz", 0),
            mode=data.get("mode"),
            snr_db=data.get("snrDb"),
            reported_at_epoch_ms=data.get("reportedAtEpochMs", 0),
            received_at_epoch_ms=data.get("receivedAtEpochMs", 0),
            extra=data.get("extra"),
            signature=data.get("signature"),
            origin_signature=data.get("originSignature"),
        )

    def __repr__(self) -> str:
        return (
            f"SignalReport(hash={self.hash!r}, reporter={self.reporter_callsign!r}, "
            f"heard={self.heard_callsign!r}, freq={self.frequency_hz}, mode={self.mode!r})"
        )


class PeerInfo:
    __slots__ = ("node_id", "callsign", "base_url", "public_key_b64", "role", "last_seen", "trusted")

    def __init__(
        self,
        node_id: str,
        callsign: Optional[str] = None,
        base_url: str = "",
        public_key_b64: str = "",
        role: NodeRole = NodeRole.ROLLING,
        last_seen: int = 0,
        trusted: bool = False,
    ):
        self.node_id = node_id
        self.callsign = callsign
        self.base_url = base_url
        self.public_key_b64 = public_key_b64
        self.role = role
        self.last_seen = last_seen
        self.trusted = trusted

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> PeerInfo:
        role_str = data.get("role", "ROLLING")
        try:
            role = NodeRole(role_str)
        except ValueError:
            role = NodeRole.ROLLING
        return cls(
            node_id=data["nodeId"],
            callsign=data.get("callsign"),
            base_url=data.get("baseUrl", ""),
            public_key_b64=data.get("publicKeyB64", data.get("publicKey", "")),
            role=role,
            last_seen=data.get("lastSeen", 0),
            trusted=data.get("trusted", False),
        )

    def __repr__(self) -> str:
        return f"PeerInfo(nodeId={self.node_id!r}, callsign={self.callsign!r}, baseUrl={self.base_url!r})"
