"""Configuration: defaults, load, save, and dotted-key get/set.

The config file is TOML at ~/.config/switchboard-at-home/config.toml (or the
path in $SAH_CONFIG). Every key has a default, so the file only needs what you
change. Change values with:

    sah config set zenoh.connect tcp/192.168.1.20:7447
"""

from __future__ import annotations

import copy
import os
import tomllib
from pathlib import Path
from typing import Any

DEFAULTS: dict[str, Any] = {
    "backend": {
        # The speech backend: "module:Class" implementing backend.SpeechBackend.
        # Default: the stt-tts core. Swap it for any other STT/TTS without touching SaH.
        "class": "switchboard_at_home.backends.stt_tts:SttTtsBackend",
    },
    "service": {
        # Key prefix for everything SaH publishes (see protocol.py)
        "prefix": "sah",
        # Doublet ids: "readable" = <app>/<n> (e.g. echo/1), "opaque" = random UUID
        "naming": "readable",
        # Seconds a new doublet waits for its app's liveliness token before closing
        "app_grace_s": 30,
    },
    "control": {
        # After a reply finishes playing: "idle" or "listening"
        "after_reply": "idle",
    },
    "output": {
        # Audio is sent paced to real time with this much lead (ms)
        "buffer_ms": 300,
    },
    "zenoh": {
        "mode": "client",
        "connect": ["tcp/localhost:7447"],
        "tls": {
            # Mutual TLS: endpoints must then be tls/<host>:<port>
            "enabled": False,
            "root_ca": "",
            "cert": "",
            "key": "",
        },
    },
}


def defaults() -> dict[str, Any]:
    return copy.deepcopy(DEFAULTS)


def config_path() -> Path:
    override = os.environ.get("SAH_CONFIG")
    if override:
        return Path(override)
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "switchboard-at-home" / "config.toml"


def _merge(base: dict, override: dict) -> dict:
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            _merge(base[key], value)
        else:
            base[key] = value
    return base


def load(path: Path | None = None) -> dict[str, Any]:
    """Defaults overlaid with the user's file, so new keys always have a value."""
    path = path or config_path()
    cfg = defaults()
    if path.exists():
        with path.open("rb") as fh:
            _merge(cfg, tomllib.load(fh))
    return cfg


# --- Minimal TOML writer (stdlib has a reader only) -------------------------

def _toml_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    if isinstance(value, list):
        return "[" + ", ".join(_toml_value(v) for v in value) + "]"
    raise TypeError(f"Unsupported config value: {value!r}")


def dumps(cfg: dict[str, Any]) -> str:
    lines: list[str] = ["# switchboard-at-home configuration", ""]

    def section(name: str, table: dict[str, Any]) -> None:
        scalars = {k: v for k, v in table.items() if not isinstance(v, dict)}
        tables = {k: v for k, v in table.items() if isinstance(v, dict)}
        if scalars:
            lines.append(f"[{name}]")
            for key, value in scalars.items():
                lines.append(f"{key} = {_toml_value(value)}")
            lines.append("")
        for key, sub in tables.items():
            section(f"{name}.{key}", sub)

    for name, table in cfg.items():
        section(name, table)
    return "\n".join(lines)


def save(cfg: dict[str, Any], path: Path | None = None) -> Path:
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(dumps(cfg))
    return path


def get(cfg: dict[str, Any], dotted: str) -> Any:
    node: Any = cfg
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            raise KeyError(dotted)
        node = node[part]
    return node


def set_value(cfg: dict[str, Any], dotted: str, raw: str) -> Any:
    """Set a dotted key, coercing the string to the existing value's type."""
    *parents, leaf = dotted.split(".")
    node = cfg
    for part in parents:
        node = node.setdefault(part, {})
    current = node.get(leaf)
    if isinstance(current, bool):
        value: Any = raw.strip().lower() in ("1", "true", "yes", "on")
    elif isinstance(current, int):
        value = int(raw)
    elif isinstance(current, float):
        value = float(raw)
    elif isinstance(current, list):
        value = [item.strip() for item in raw.split(",") if item.strip()]
    else:
        value = raw
    node[leaf] = value
    return value
