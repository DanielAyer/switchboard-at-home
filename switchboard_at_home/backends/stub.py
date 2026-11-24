"""A backend with no engines: fake transcripts and silent speech. For tests and wiring checks."""

from __future__ import annotations

from ..backend import SpeechBackend, SttStream

RATE = 24000


class _Stream(SttStream):
    def __init__(self, rate: int) -> None:
        self.rate = rate
        self.bytes = 0

    def feed(self, pcm: bytes) -> None:
        self.bytes += len(pcm)

    def finalize(self) -> str:
        seconds = self.bytes / 2 / self.rate
        self.bytes = 0
        return f"(stub transcript of {seconds:.1f} s of audio)"


class StubBackend(SpeechBackend):
    @property
    def output_rate(self) -> int:
        return RATE

    def open_stream(self) -> SttStream:
        return _Stream(self.input_rate)

    def synthesize(self, sentence: str) -> bytes:
        return b"\x00\x00" * int(RATE * 0.4)  # 0.4 s of silence per sentence
