# switchboard-at-home (SaH)

SaH routes speech and text between people and agents on a [Zenoh](https://zenoh.io) router. It holds the sessions, the button state machine and the Zenoh plumbing; a separate **speech backend** does the conversion. The default backend is [stt-tts](https://github.com/DanielAyer/stt-tts), which is now a plain call/response converter with no Zenoh in it.

```
apps (agents, phone)  ──Zenoh──  switchboard-at-home  ──function calls──  speech backend (stt-tts)
```

Today SaH runs the doublet service that used to be `stt-tts serve`. The design for where it is going (directory, call-by-name routing, reply queue) is in the design doc.

Interactive architecture diagrams (topology, voice round-trip, Protocol v2 keyspace, OpenSSL PKI/mTLS, git evolution, and runtime deployment) live in [docs/architecture.html](docs/architecture.html) — open it directly in a browser, no server or external assets needed.

## Install

Needs Python 3.11+, a Zenoh router (zenohd 1.x), and, for the default backend, stt-tts installed first.

```bash
git clone https://github.com/DanielAyer/switchboard-at-home
cd switchboard-at-home
./install.sh            # run again after every git pull
sah backend             # check that the speech backend loads
```

`install.sh` puts the program in `~/.local/opt/switchboard-at-home` with its own venv (plus `eclipse-zenoh`) and writes the `sah` launcher to `~/.local/bin`. If stt-tts is installed, its code and engine packages are linked into SaH's venv read-only, so both use the same engines, models and stt-tts config. `./install.sh --uninstall` removes the program and launcher and keeps your config.

## Run

```bash
sah serve                                  # the voice service; needs a router
sah doublets                               # list open doublets
sah test-app --name echo                   # a stand-in agent: replies "You said: ..."
sah test-terminal clip.wav --doublet echo -o reply.wav
```

`test-terminal` presses the button, streams the WAV in real time, presses again, saves the spoken reply, and prints the transcript and the time from releasing the button to the first reply audio. If the doublet you name isn't open, it lists the ones that are.

## Speech backend

SaH asks the backend for exactly two things: a speech-to-text stream (`feed`, `partial`, `finalize`) and text-to-speech per sentence. The interface is `switchboard_at_home/backend.py`. Choose the backend in config:

```bash
sah config set backend.class switchboard_at_home.backends.stt_tts:SttTtsBackend   # default
sah config set backend.class switchboard_at_home.backends.stub:StubBackend        # no engines (tests)
sah config set backend.class my_package.my_module:MyBackend                       # your own
```

A backend is built as `Class(options)` with the `[backend]` table, so it can read its own keys there. The stt-tts backend reads one optional key, `backend.stt_tts_config`, to point at a different stt-tts config file; engines and voices are set in stt-tts (`stt-tts setup engines`).

## Configuration

`~/.config/switchboard-at-home/config.toml` (or `$SAH_CONFIG`). Every key has a default.

| Setting | Default | Meaning |
| --- | --- | --- |
| `backend.class` | stt-tts backend | speech backend, `module:Class` |
| `zenoh.connect` | `tcp/localhost:7447` | router endpoints |
| `zenoh.tls.*` | off | mutual TLS: `root_ca`, `cert`, `key`; endpoints become `tls/host:port` |
| `service.prefix` | `sah` | root of every key below |
| `service.naming` | `readable` | `readable` (`echo/1`) or `opaque` (random UUID) doublet ids |
| `service.app_grace_s` | `30` | close a doublet if its app hasn't shown up within this long |
| `control.after_reply` | `idle` | after a reply plays: `idle` or `listening` |
| `output.buffer_ms` | `300` | lead kept on the terminal when pacing audio out |

`sah config show | get KEY | set KEY VALUE | path`.

## Doublet service

Any app (an LLM bridge, a script) asks the service for a **doublet**: a private pair of streams, text on the app's side and audio on the terminal's side. A terminal (the phone app now, a standalone device later) lists the open doublets, picks one, and attaches its mic and speaker. The service runs the button state machine and calls the backend for speech to text and text to speech.

**Locked to publish, open to listen.** Only the app that registered a doublet can send it text to speak, and only one terminal at a time can attach and send audio or presses. Anyone on the router can listen. Each publish carries the sender's token (from registration or attach) in its Zenoh attachment; anything without the right token is ignored. Tokens are not encrypted on the wire: use TLS on the router and keep it off the internet.

**Lifetimes.** The app and the terminal each hold a Zenoh liveliness token. If the app goes away the service closes the doublet; if the terminal goes away the lock is released.

### Keys

`<p>` is `service.prefix`; `<id>` is the doublet id the service assigns (`<app>/<n>` by default, a UUID with `service.naming = "opaque"`). App names are cleaned to a key-safe slug. Clients always use the key names returned by `register` and `doublets`, never build them.

| Key | Kind | Who writes | Payload |
| --- | --- | --- | --- |
| `<p>/register` | query | app | `{"name": "..."}` → id, `app_token`, the doublet's keys and formats |
| `<p>/doublets` | query | anyone | open doublets: id, name, state, whether a terminal holds it |
| `<p>/<id>/attach` | query | terminal | `{"terminal": "name"}` → `terminal_token`, or refused with `held_by` |
| `<p>/<id>/detach` | query | terminal | `{"terminal_token": "..."}` |
| `<p>/<id>/control` | pub | terminal | `{"op": "press"}` |
| `<p>/<id>/audio/in` | pub | terminal | 16 kHz mono 16-bit PCM, `audio/L16;rate=16000` |
| `<p>/<id>/text/out` | pub | service | `{"text", "doublet", "terminal", "t"}` when you finish speaking |
| `<p>/<id>/text/in` | pub | app | UTF-8 text to speak |
| `<p>/<id>/audio/out` | pub | service | PCM at the backend's rate, `audio/L16;rate=24000`, paced in real time |
| `<p>/<id>/events` | pub | service | `listening`, `partial`, `thinking`, `speaking`, `interrupted` (with `spoken_text`), `playback_done`, `idle`, `reply_cancelled`, `reply_dropped`, `closed` |
| `<p>/live/<id>/app`, `.../term` | liveliness | app, terminal | presence |

A terminal should drop any buffered audio when it gets `interrupted`. The registration reply has a `server_public_key` field, `null` for now, reserved for end-to-end encryption.

## Phone terminal (Android)

`android/` is a Kotlin app that lists doublets, attaches, and drives the one button with the phone's mic and speaker. Open it in Android Studio and run it on an Android 11+ phone; see [android/README.md](android/README.md). Its default prefix is `sah`.

## Development

```bash
python3 -m unittest discover -s tests                                # stub backend; service tests need eclipse-zenoh
STT_TTS_PATH=../stt-tts python3 -m unittest discover -s tests        # also test the stt-tts backend
```

The service tests start a private Zenoh router on a loopback port, so they need no external router.
