#!/usr/bin/env bash
# Prepares the emulator before its snapshot is saved: the image has no text-to-speech
# engine, so add eSpeak NG (from F-Droid) for read-aloud to be exercised.
set -x
ESPEAK=com.reecedunn.espeak
code=$(curl -fsSL "https://f-droid.org/api/v1/packages/$ESPEAK" | python3 -c 'import json,sys; print(json.load(sys.stdin)["suggestedVersionCode"])' || true)
if [ -n "$code" ] && curl -fsSL --retry 3 -o espeak.apk "https://f-droid.org/repo/${ESPEAK}_${code}.apk"; then
  adb install -r espeak.apk && adb shell settings put secure tts_default_synth $ESPEAK || true
  # eSpeak unpacks its voice data the first time its own screen opens.
  adb shell am start -W -n $ESPEAK/.DownloadVoiceData || true
  sleep 15
  adb shell input keyevent KEYCODE_BACK; sleep 1
else
  echo "Couldn't fetch eSpeak NG; read-aloud won't be testable"
fi
