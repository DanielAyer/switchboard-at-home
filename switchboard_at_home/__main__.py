"""switchboard-at-home (sah): routes speech and text between people and agents on Zenoh.

    sah serve                         run the doublet voice service
    sah doublets                      list open doublets
    sah test-app [--name echo]        an app that answers "You said: ..."
    sah test-terminal clip.wav        speak a WAV into a doublet, save the reply
    sah backend                       show which speech backend is configured, and load it
    sah config show|get|set|path      read or change settings
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from pathlib import Path

from . import __version__, config


def cmd_serve(cfg, args) -> int:
    """Run the doublet voice service on Zenoh until Ctrl-C."""
    import signal
    import threading

    from .service import VoiceService

    logging.getLogger("switchboard_at_home").setLevel(logging.INFO)
    service = VoiceService(cfg)
    print(f"sah serving on Zenoh ({', '.join(cfg['zenoh']['connect'])}), prefix "
          f"'{cfg['service']['prefix']}'. Backend: {cfg['backend']['class']} "
          f"(audio out {service.backend.output_rate} Hz). Ctrl-C to stop.", flush=True)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    try:
        stop.wait()
    except KeyboardInterrupt:
        pass
    service.close()
    return 0


def cmd_doublets(cfg, args) -> int:
    from .client import list_doublets
    from .zenoh_session import open_session

    session = open_session(cfg)
    try:
        found = list_doublets(session, cfg["service"]["prefix"])
    finally:
        session.close()
    if not found:
        print("No open doublets.")
    for d in found:
        holder = d["terminal"] or "free"
        print(f"{d['id']:<16} {d['name']:<20} {d['state']:<10} terminal: {holder}")
    return 0


def cmd_test_app(cfg, args) -> int:
    from .client import run_test_app
    return run_test_app(cfg, args.name)


def cmd_test_terminal(cfg, args) -> int:
    from .client import run_test_terminal
    return run_test_terminal(cfg, args.file, doublet_name=args.doublet, out_path=args.output)


def cmd_backend(cfg, args) -> int:
    from . import backend

    print(f"backend.class = {cfg['backend']['class']}")
    b = backend.load(cfg)
    try:
        print(f"loaded: audio in {b.input_rate} Hz, audio out {b.output_rate} Hz")
    finally:
        b.close()
    return 0


def cmd_config(cfg, args) -> int:
    path = config.config_path()
    if args.action == "path":
        print(path)
    elif args.action == "show":
        print(config.dumps(cfg))
    elif args.action == "get":
        print(json.dumps(config.get(cfg, args.key)))
    elif args.action == "set":
        value = config.set_value(cfg, args.key, args.value)
        config.save(cfg, path)
        print(f"{args.key} = {json.dumps(value)}  (saved to {path})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="sah", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("--config", type=Path, help="config file (default: XDG config dir)")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("serve", help="run the doublet voice service on Zenoh").set_defaults(func=cmd_serve)
    sub.add_parser("doublets", help="list open doublets").set_defaults(func=cmd_doublets)
    p = sub.add_parser("test-app", help="test app: answers 'You said: ...'")
    p.add_argument("--name", default="echo")
    p.set_defaults(func=cmd_test_app)
    p = sub.add_parser("test-terminal", help="test terminal: speak a WAV into a doublet")
    p.add_argument("file")
    p.add_argument("--doublet", help="doublet name or id (default: the first free one)")
    p.add_argument("-o", "--output", default="reply.wav")
    p.set_defaults(func=cmd_test_terminal)
    sub.add_parser("backend", help="show and load the speech backend").set_defaults(func=cmd_backend)
    p = sub.add_parser("config")
    p.add_argument("action", choices=["show", "get", "set", "path"])
    p.add_argument("key", nargs="?")
    p.add_argument("value", nargs="?")
    p.set_defaults(func=cmd_config)

    args = parser.parse_args(argv)
    if args.command == "config" and args.action in ("get", "set") and not args.key:
        parser.error("config get/set needs a key, e.g. zenoh.connect")
    if args.command == "config" and args.action == "set" and args.value is None:
        parser.error("config set needs a value")
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING)
    if args.config:
        os.environ["SAH_CONFIG"] = str(args.config)
    try:
        return args.func(config.load(), args)
    except KeyError as exc:
        print(f"sah: unknown config key {exc}", file=sys.stderr)
        return 2
    except (ValueError, RuntimeError, TypeError) as exc:
        print(f"sah: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
