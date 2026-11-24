#!/usr/bin/env bash
# switchboard-at-home installer.
#
#   ./install.sh               install or update (run again after every git pull)
#   ./install.sh --uninstall   remove the program and the `sah` launcher (config is kept)
#
# Installs into ~/.local/opt/switchboard-at-home with its own venv and writes
# ~/.local/bin/sah. The default speech backend is stt-tts: if stt-tts is
# installed, its code and engine packages are linked into SaH's venv (read-only),
# so both use the same engines and models. Without stt-tts, SaH still installs;
# pick another backend with: sah config set backend.class module:Class
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="${SAH_APP_DIR:-$HOME/.local/opt/switchboard-at-home}"
BIN="${SAH_BIN_DIR:-$HOME/.local/bin}"
LAUNCHER="$BIN/sah"

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -rf "$APP" "$LAUNCHER"
  echo "Removed $APP and $LAUNCHER. Config kept in ~/.config/switchboard-at-home."
  exit 0
fi

# --- find stt-tts ---------------------------------------------------------------
STT_LAUNCHER="$(command -v stt-tts || true)"
[[ -z "$STT_LAUNCHER" && -x "$BIN/stt-tts" ]] && STT_LAUNCHER="$BIN/stt-tts"
STT_APP="" STT_PY="" STT_CONFIG=""
if [[ -n "$STT_LAUNCHER" ]]; then
  STT_APP="$(sed -n 's/^export PYTHONPATH="\([^$"]*\).*/\1/p' "$STT_LAUNCHER" | head -1)"
  STT_PY="$(sed -n 's/^exec "\([^"]*\)".*/\1/p' "$STT_LAUNCHER" | head -1)"
  STT_CONFIG="$(sed -n 's/^export STT_TTS_CONFIG="\([^"]*\)".*/\1/p' "$STT_LAUNCHER" | head -1)"
fi
if [[ -n "$STT_APP" && -x "$STT_PY" && -f "$STT_APP/stt_tts/core.py" ]]; then
  echo "Found stt-tts in $STT_APP"
  PY="$STT_PY"
else
  if [[ -n "$STT_APP" && ! -f "$STT_APP/stt_tts/core.py" ]]; then
    echo "stt-tts is installed but has no stt_tts/core.py: update it first"
    echo "(git pull in the stt-tts repo, then run its ./install.sh and choose to modify/update)."
    exit 1
  fi
  echo "stt-tts not found: installing without it. The default backend will not load"
  echo "until stt-tts is installed (then run this again)."
  STT_APP="" STT_PY=""
  PY=""
  for candidate in python3 python3.13 python3.12 python3.11; do
    if command -v "$candidate" >/dev/null 2>&1 &&
       "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null; then
      PY="$candidate"; break
    fi
  done
  [[ -z "$PY" ]] && { echo "Need Python 3.11 or newer (sudo apt install python3 python3-venv)"; exit 1; }
fi

# --- program files and venv --------------------------------------------------------
echo "Copying program files to $APP"
mkdir -p "$APP"
rm -rf "$APP/switchboard_at_home"
cp -r "$HERE/switchboard_at_home" "$APP/"
find "$APP/switchboard_at_home" -name __pycache__ -prune -exec rm -rf {} +
cp "$HERE/README.md" "$APP/" 2>/dev/null || true

if [[ ! -x "$APP/venv/bin/python" ]]; then
  echo "Creating venv"
  "$PY" -m venv "$APP/venv" || { echo "venv failed: sudo apt install python3-venv"; exit 1; }
fi
VPY="$APP/venv/bin/python"

echo "Installing Zenoh (pip install eclipse-zenoh)"
"$VPY" -m pip install --quiet --upgrade eclipse-zenoh

SITE="$("$VPY" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')"
LINK="$SITE/stt_tts_link.pth"
if [[ -n "$STT_APP" ]]; then
  STT_SITE="$("$STT_PY" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')"
  printf '%s\n%s\n' "$STT_APP" "$STT_SITE" > "$LINK"
  echo "Linked stt-tts code and engines into SaH's venv"
else
  rm -f "$LINK"
fi

# --- launcher ------------------------------------------------------------------------
mkdir -p "$BIN"
{
  echo '#!/bin/sh'
  echo '# switchboard-at-home launcher (written by install.sh)'
  echo "export PYTHONPATH=\"$APP\${PYTHONPATH:+:\$PYTHONPATH}\""
  [[ -n "$STT_CONFIG" ]] && echo "export STT_TTS_CONFIG=\"$STT_CONFIG\""
  echo "exec \"$VPY\" -m switchboard_at_home \"\$@\""
} > "$LAUNCHER"
chmod 755 "$LAUNCHER"
echo "Wrote $LAUNCHER"

echo
"$LAUNCHER" backend || echo "(the backend did not load; see the message above)"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "Add $BIN to your PATH to run 'sah'.";; esac
echo "Done. Start the service with: sah serve"
