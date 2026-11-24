"""Default backend: the stt-tts conversion core (stt_tts.core).

stt-tts reads its own config (engines, voice, model paths). Optional key in
SaH's [backend] table:

    stt_tts_config = "/path/to/stt-tts/config.toml"   # default: stt-tts's usual file
"""

from __future__ import annotations

from pathlib import Path

from ..backend import SpeechBackend, SttStream


class _Stream(SttStream):
    def __init__(self, stream) -> None:
        self._s = stream

    def feed(self, pcm: bytes) -> None:
        self._s.feed(pcm)

    def partial(self) -> str:
        return self._s.partial()

    def finalize(self) -> str:
        return self._s.finalize()

    def close(self) -> None:
        self._s.close()


class SttTtsBackend(SpeechBackend):
    def __init__(self, options: dict | None = None) -> None:
        super().__init__(options)
        try:
            from stt_tts import config as stt_config
            from stt_tts.core import Core
        except ImportError as exc:
            raise RuntimeError("The stt-tts backend needs stt-tts importable "
                               "(install stt-tts, then run SaH's install.sh)") from exc
        path = self.options.get("stt_tts_config")
        cfg = stt_config.load(Path(path).expanduser()) if path else None
        self.core = Core(cfg)
        self.input_rate = self.core.input_rate

    @property
    def output_rate(self) -> int:
        return self.core.output_rate

    def open_stream(self) -> SttStream:
        return _Stream(self.core.open_stream())

    def sentences(self, text: str) -> list[str]:
        return self.core.sentences(text)

    def synthesize(self, sentence: str) -> bytes:
        return self.core.synthesize(sentence)

    def close(self) -> None:
        self.core.close()
