"""The speech backend: the only thing SaH needs from a speech engine.

SaH routes and keeps state; the backend just converts. Any class with this
shape works. Pick one in config:

    [backend]
    class = "switchboard_at_home.backends.stt_tts:SttTtsBackend"   # default
    # class = "switchboard_at_home.backends.stub:StubBackend"      # no engines, for tests
    # class = "my_package.my_module:MyBackend"                     # your own

The class is built as Class(options), where options is the [backend] table
(so a backend can read its own keys from there).

Audio is 16-bit little-endian mono PCM: input_rate into speech-to-text,
output_rate out of text-to-speech.
"""

from __future__ import annotations

import importlib
import re
from abc import ABC, abstractmethod


class SttStream(ABC):
    """One utterance being transcribed. Used by one thread at a time."""

    @abstractmethod
    def feed(self, pcm: bytes) -> None: ...

    def partial(self) -> str:
        """In-progress text; "" if the engine doesn't offer it."""
        return ""

    @abstractmethod
    def finalize(self) -> str:
        """Final text for everything fed so far; the stream can then be reused."""

    def close(self) -> None:
        pass


class SpeechBackend(ABC):
    input_rate: int = 16000

    def __init__(self, options: dict | None = None) -> None:
        self.options = options or {}

    @property
    @abstractmethod
    def output_rate(self) -> int: ...

    @abstractmethod
    def open_stream(self) -> SttStream: ...

    def sentences(self, text: str) -> list[str]:
        """Split text so playback can start after the first sentence."""
        return [p for p in re.split(r"(?<=[.!?])\s+", text.strip()) if p]

    @abstractmethod
    def synthesize(self, sentence: str) -> bytes:
        """PCM at output_rate for one sentence. SaH calls this from one thread at a time."""

    def close(self) -> None:
        pass


def load(cfg: dict) -> SpeechBackend:
    ref = cfg["backend"]["class"]
    module, _, attr = ref.partition(":")
    if not module or not attr:
        raise ValueError(f"backend.class must look like 'module:Class', not '{ref}'")
    try:
        cls = getattr(importlib.import_module(module), attr)
    except (ImportError, AttributeError) as exc:
        raise RuntimeError(f"Could not load speech backend '{ref}': {exc}") from exc
    backend = cls(dict(cfg["backend"]))
    if not isinstance(backend, SpeechBackend):
        raise TypeError(f"{ref} is not a SpeechBackend")
    return backend
