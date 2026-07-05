from __future__ import annotations

import time
import uuid
from typing import Any, Dict, List, Optional

import httpx

from hamreporter.crypto import Ed25519KeyPair, canonical_http_signature, sha256_hex
from hamreporter.models import NodeRole, PeerInfo, SignalReport


class HamReporterClient:
    def __init__(
        self,
        base_url: str,
        key_pair: Ed25519KeyPair,
        callsign: str,
        node_id: Optional[str] = None,
        timeout: float = 15.0,
    ):
        self.base_url = base_url.rstrip("/")
        self.key_pair = key_pair
        self.callsign = callsign.upper().strip()
        self.node_id = node_id or self.callsign
        self._client = httpx.Client(timeout=timeout, base_url=self.base_url)

    def _sign_headers(
        self,
        method: str,
        path: str,
        body: Optional[bytes] = None,
        extra_headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, str]:
        ts = int(time.time() * 1000)
        nonce = str(uuid.uuid4())
        canon = canonical_http_signature(method, path, ts, nonce, body)
        sig = self.key_pair.sign(canon)
        headers: Dict[str, str] = {
            "X-Callsign": self.callsign,
            "X-Public-Key": self.key_pair.public_key_b64,
            "X-Timestamp": str(ts),
            "X-Nonce": nonce,
            "X-Signature": sig,
            "Content-Type": "application/json",
        }
        if extra_headers:
            headers.update(extra_headers)
        return headers

    # ── Public endpoints ──────────────────────────────────────────

    def health(self) -> Dict[str, Any]:
        resp = self._client.get("/api/v1/health")
        resp.raise_for_status()
        return resp.json()

    def node_info(self) -> PeerInfo:
        resp = self._client.get("/api/v1/nodes/info")
        resp.raise_for_status()
        return PeerInfo.from_dict(resp.json())

    def peers(self) -> List[PeerInfo]:
        resp = self._client.get("/api/v1/nodes/peers")
        resp.raise_for_status()
        return [PeerInfo.from_dict(p) for p in resp.json()]

    def active_callsigns(self) -> List[Dict[str, Any]]:
        resp = self._client.get("/api/v1/callsigns/active")
        resp.raise_for_status()
        return resp.json()

    def get_report(self, hash: str) -> Optional[SignalReport]:
        resp = self._client.get(f"/api/v1/reports/{hash}")
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        return SignalReport.from_dict(resp.json())

    def query_reports(
        self,
        *,
        reporter: Optional[str] = None,
        heard: Optional[str] = None,
        freq_min: Optional[int] = None,
        freq_max: Optional[int] = None,
        mode: Optional[str] = None,
        reporter_grid: Optional[str] = None,
        heard_grid: Optional[str] = None,
        since: Optional[int] = None,
        until: Optional[int] = None,
        limit: int = 200,
        sort: str = "desc",
    ) -> List[SignalReport]:
        params: Dict[str, str] = {"limit": str(limit), "sort": sort}
        if reporter:
            params["reporter"] = reporter
        if heard:
            params["heard"] = heard
        if freq_min is not None:
            params["freqMin"] = str(freq_min)
        if freq_max is not None:
            params["freqMax"] = str(freq_max)
        if mode:
            params["mode"] = mode
        if reporter_grid:
            params["reporterGrid"] = reporter_grid
        if heard_grid:
            params["heardGrid"] = heard_grid
        if since is not None:
            params["since"] = str(since)
        if until is not None:
            params["until"] = str(until)
        resp = self._client.get("/api/v1/reports", params=params)
        resp.raise_for_status()
        return [SignalReport.from_dict(r) for r in resp.json()]

    # ── Authenticated endpoints ───────────────────────────────────

    def submit_report(self, report: SignalReport) -> SignalReport:
        report.reporter_public_key = self.key_pair.public_key_b64
        if report.reporter_callsign != self.callsign:
            raise ValueError(
                f"Report callsign {report.reporter_callsign!r} does not match client callsign {self.callsign!r}"
            )

        canonical = report.canonical_reporter_payload()
        report.hash = sha256_hex(canonical)
        report.signature = self.key_pair.sign(canonical)

        body = report.to_dict()
        import json

        body_bytes = json.dumps(body).encode("utf-8")
        path = "/api/v1/reports"
        headers = self._sign_headers("POST", path, body_bytes)

        resp = self._client.post(path, content=body_bytes, headers=headers)
        resp.raise_for_status()
        return SignalReport.from_dict(resp.json())

    def heartbeat(self) -> Dict[str, Any]:
        import json

        body_bytes = b"{}"
        path = "/api/v1/heartbeat"
        headers = self._sign_headers("POST", path, body_bytes)

        resp = self._client.post(path, content=body_bytes, headers=headers)
        resp.raise_for_status()
        return resp.json()

    # ── Federation endpoints ──────────────────────────────────────

    def federation_announce(self, report: SignalReport) -> SignalReport:
        import json

        body = report.to_dict()
        body_bytes = json.dumps(body).encode("utf-8")
        path = "/api/v1/federation/announce"
        headers = self._sign_headers("POST", path, body_bytes, {"X-Node-Id": self.node_id})

        resp = self._client.post(path, content=body_bytes, headers=headers)
        resp.raise_for_status()
        return SignalReport.from_dict(resp.json())

    def federation_origins(self) -> Dict[str, int]:
        path = "/api/v1/federation/origins"
        headers = self._sign_headers("GET", path, None, {"X-Node-Id": self.node_id})

        resp = self._client.get(path, headers=headers)
        resp.raise_for_status()
        return resp.json()

    def federation_since(
        self, origin: str, since: int, limit: int = 500
    ) -> List[SignalReport]:
        path = f"/api/v1/federation/since?origin={origin}&since={since}&limit={limit}"
        headers = self._sign_headers("GET", path, None, {"X-Node-Id": self.node_id})

        resp = self._client.get(path, headers=headers)
        resp.raise_for_status()
        return [SignalReport.from_dict(r) for r in resp.json()]

    def federation_hello(self, node_id: str, base_url: str, role: NodeRole = NodeRole.ROLLING) -> Dict[str, Any]:
        import json

        body = {
            "nodeId": node_id,
            "callsign": self.callsign,
            "baseUrl": base_url,
            "publicKey": self.key_pair.public_key_b64,
            "role": role.value,
        }
        body_bytes = json.dumps(body).encode("utf-8")
        path = "/api/v1/federation/hello"
        headers = self._sign_headers("POST", path, body_bytes, {"X-Node-Id": self.node_id})

        resp = self._client.post(path, content=body_bytes, headers=headers)
        resp.raise_for_status()
        return resp.json()

    # ── Lifecycle ─────────────────────────────────────────────────

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> HamReporterClient:
        return self

    def __exit__(self, *args: Any) -> None:
        self.close()
