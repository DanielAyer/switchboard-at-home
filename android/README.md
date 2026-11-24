# SaH terminal (Android)

A phone terminal for the switchboard-at-home doublet service: list doublets, attach to one, and talk with one button.
Kotlin + Jetpack Compose, Zenoh via `org.eclipse.zenoh:zenoh-kotlin-android:1.10.1` (same 1.10 line as zenohd 1.10.2).

## Build and install

Needs Android Studio (JDK 17 bundled) and a phone on **Android 11+** (zenoh-kotlin requires API 30).

1. Android Studio → **Open** → this `android/` folder. Let Gradle sync (it downloads Gradle 8.10.2, AGP, Zenoh).
2. Enable USB debugging on the phone, plug it in, press **Run**.

Command line instead: `cd android && ./gradlew installDebug` (needs `ANDROID_HOME` or `local.properties` with `sdk.dir=`).

## Use

1. On nidavellir: `sah serve` running, plus an app, e.g. `sah test-app --name echo`.
2. In the app: enter the router endpoint, e.g. `tcp/192.168.1.20:7447` (use the server's IP if the
   hostname doesn't resolve on the phone), keep prefix `sah`, **Connect**.
3. Pick a doublet → **Attach** (grants mic permission the first time).
4. The big button follows the server's state:
   Idle *tap to talk* → Listening *tap to send* → Thinking *tap to cancel* → Speaking *tap to interrupt*.

The mic only runs while the doublet is Listening (half-duplex). The phone's `VOICE_COMMUNICATION` input
gives you the built-in echo canceller and noise suppressor for free. The screen stays on while attached.

## How it maps to the protocol

| Step | Code |
|---|---|
| list `<p>/doublets` | `TerminalViewModel.loadDoublets` |
| subscribe events, audio_out, text_out (+ text_in for the reply text) | `Terminal` init |
| query `keys.attach` → `terminal_token`, `audio_out` rate | `Terminal` init |
| liveliness token on `keys.live_term` | `Terminal` init |
| `{"op":"press"}` on `keys.control`, token in attachment | `Terminal.press` |
| 640-byte frames on `keys.audio_in`, encoding `audio/L16;rate=16000` | `Mic` → `Terminal.sendAudio` |
| flush playback on `interrupted` / `reply_cancelled` | `TerminalViewModel.onEvent` → `Speaker.flush` |
| query `keys.detach` | `Terminal.detach` |

Same order as `Terminal` in `switchboard_at_home/client.py`. Key names always come from the server.

## Not yet

- Physical button (the press path is one call, `TerminalViewModel.press()`, ready to wire).
- Running in the background (would need a foreground service of type `microphone`).
- TLS to the router, and the planned end-to-end encryption.
