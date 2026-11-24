"""Speech backend loading and the stt-tts adapter. Run with: python3 -m unittest discover -s tests

The stt-tts tests run only when stt_tts is importable (installed, or STT_TTS_PATH
points at an stt-tts checkout); they use its "none" engines, so no models.
"""

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
if os.environ.get("STT_TTS_PATH"):
    sys.path.insert(0, os.environ["STT_TTS_PATH"])

from switchboard_at_home import backend, config  # noqa: E402

HAS_STT_TTS = importlib.util.find_spec("stt_tts") is not None


class LoadTest(unittest.TestCase):
    def test_default_is_stt_tts(self):
        self.assertEqual(config.defaults()["backend"]["class"],
                         "switchboard_at_home.backends.stt_tts:SttTtsBackend")

    def test_stub_round_trip(self):
        cfg = config.defaults()
        cfg["backend"]["class"] = "switchboard_at_home.backends.stub:StubBackend"
        b = backend.load(cfg)
        s = b.open_stream()
        s.feed(b"\x00\x00" * 16000)
        self.assertIn("1.0 s", s.finalize())
        self.assertEqual(b.sentences("One. Two? Three!"), ["One.", "Two?", "Three!"])
        self.assertEqual(len(b.synthesize("Hi.")), int(b.output_rate * 0.4) * 2)

    def test_bad_refs_are_explained(self):
        cfg = config.defaults()
        for ref in ("no_colon", "missing.module:X", "switchboard_at_home.config:defaults"):
            cfg["backend"]["class"] = ref
            with self.assertRaises((ValueError, RuntimeError, TypeError)):
                backend.load(cfg)


@unittest.skipUnless(HAS_STT_TTS, "stt_tts not importable")
class SttTtsBackendTest(unittest.TestCase):
    def test_converts_with_stub_engines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "stt-tts.toml"
            path.write_text('[stt]\nengine = "none"\n\n[tts]\nengine = "none"\n')
            cfg = config.defaults()
            cfg["backend"]["stt_tts_config"] = str(path)
            b = backend.load(cfg)
            try:
                s = b.open_stream()
                s.feed(b"\x00\x00" * 16000)
                self.assertIn("stub transcript", s.finalize())
                self.assertGreater(len(b.synthesize("Hello there.")), 0)
                self.assertEqual(b.input_rate, 16000)
            finally:
                b.close()


class ConfigTest(unittest.TestCase):
    def test_save_load_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "c.toml"
            cfg = config.defaults()
            config.set_value(cfg, "zenoh.connect", "tcp/10.0.0.5:7447")
            config.set_value(cfg, "output.buffer_ms", "200")
            config.save(cfg, path)
            back = config.load(path)
            self.assertEqual(back["zenoh"]["connect"], ["tcp/10.0.0.5:7447"])
            self.assertEqual(back["output"]["buffer_ms"], 200)
            self.assertEqual(back["service"]["prefix"], "sah")


if __name__ == "__main__":
    unittest.main()
