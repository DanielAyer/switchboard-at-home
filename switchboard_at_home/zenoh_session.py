"""Open a Zenoh session from SaH's [zenoh] config."""

from __future__ import annotations

import json


def open_session(cfg: dict):
    try:
        import zenoh
    except ImportError as exc:
        raise RuntimeError("switchboard-at-home needs Zenoh: pip install eclipse-zenoh") from exc
    z = cfg["zenoh"]
    zcfg = zenoh.Config()
    zcfg.insert_json5("mode", json.dumps(z["mode"]))
    zcfg.insert_json5("connect/endpoints", json.dumps(z["connect"]))
    tls = z.get("tls", {})
    if tls.get("enabled"):
        if not all(e.startswith("tls/") for e in z["connect"]):
            raise RuntimeError("zenoh.tls.enabled is on, so zenoh.connect endpoints must be "
                               "tls/<host>:<port>")
        zcfg.insert_json5("transport/link/tls/root_ca_certificate", json.dumps(tls["root_ca"]))
        zcfg.insert_json5("transport/link/tls/enable_mtls", "true")
        zcfg.insert_json5("transport/link/tls/connect_certificate", json.dumps(tls["cert"]))
        zcfg.insert_json5("transport/link/tls/connect_private_key", json.dumps(tls["key"]))
    try:
        return zenoh.open(zcfg)
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"Could not connect to Zenoh at {', '.join(z['connect'])}: {exc}") from exc
