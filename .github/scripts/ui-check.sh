#!/usr/bin/env bash
# Runs the debug APK on an emulator with a real Project Gutenberg book and captures
# screenshots of the library and reader, so layout bugs show up without a phone.
set -x
PKG=com.folio.reader
OUT=shots
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk

# The emulator image has no text-to-speech engine; add eSpeak NG (from F-Droid) so
# read-aloud can be exercised.
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
adb shell wm size > "$OUT/screen.txt"
read -r W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
W=${W:-1080}; H=${H:-2400}

curl -fsSL --retry 3 -o book.epub https://www.gutenberg.org/cache/epub/2554/pg2554-images-3.epub || exit 1
adb push book.epub /data/local/tmp/book.epub

# First launch creates the app's data directory.
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell am start -W -n $PKG/.MainActivity
sleep 5
adb exec-out screencap -p > "$OUT/0-library-empty.png"
adb shell am force-stop $PKG

# Seed the library with the book, as if it had been imported.
adb shell run-as $PKG mkdir -p files/books
adb shell run-as $PKG cp /data/local/tmp/book.epub files/books/test.epub
printf '%s' '[{"id":"test","title":"Crime and Punishment","author":"Fyodor Dostoyevsky","fileName":"test.epub","coverFileName":null,"addedAt":1,"lastOpenedAt":0,"progress":0,"chapterIndex":0,"chapterOffset":0}]' \
  | adb exec-in run-as $PKG sh -c 'cat > files/library.json'

adb logcat -c
adb shell am start -W -n $PKG/.MainActivity --es open_book test
sleep 8
adb exec-out screencap -p > "$OUT/1-reader-first-page.png"

swipe_next() { adb shell input swipe $((W * 8 / 10)) $((H / 2)) $((W * 2 / 10)) $((H / 2)) 150; sleep 2; }
swipe_next
adb exec-out screencap -p > "$OUT/2-after-one-swipe.png"
swipe_next; swipe_next; swipe_next
adb exec-out screencap -p > "$OUT/3-after-four-swipes.png"

# Tap the right edge to turn a page, then the middle for the controls.
adb shell input tap $((W * 9 / 10)) $((H / 2)); sleep 2
adb exec-out screencap -p > "$OUT/4-after-tap.png"
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
adb exec-out screencap -p > "$OUT/5-controls.png"

adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 3
adb exec-out screencap -p > "$OUT/6-library.png"

# Open a few files further in, where the story text starts.
unzip -l book.epub > "$OUT/book-files.txt" || true
for idx in 2 3 4; do
  adb shell am force-stop $PKG
  printf '[{"id":"test","title":"Crime and Punishment","author":"Fyodor Dostoyevsky","fileName":"test.epub","coverFileName":null,"addedAt":1,"lastOpenedAt":0,"progress":0,"chapterIndex":%d,"chapterOffset":0}]' "$idx" \
    | adb exec-in run-as $PKG sh -c 'cat > files/library.json'
  adb shell am start -W -n $PKG/.MainActivity --es open_book test
  sleep 8
  adb exec-out screencap -p > "$OUT/7-file-$idx.png"
  swipe_next
  adb exec-out screencap -p > "$OUT/7-file-$idx-next.png"
done

# Bookmarks and read-aloud, on the first chapter of the story (file 3).
# Taps controls by their accessibility label, read from a UI dump.
find_label() {
  local xy try
  for try in 1 2 3; do
  adb shell rm -f /sdcard/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml 2>&1 | tail -1 >> "$OUT/dumps.txt" || true
  adb shell cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
  xy=$(python3 - "$1" "${2:-}" <<'PY'
import html, re, sys
label = sys.argv[1].encode().decode('unicode_escape')
contains = sys.argv[2] == 'contains'
xml = open('ui.xml', encoding='utf-8', errors='ignore').read()
for node in re.findall(r'<node [^>]*>', xml):
    desc = html.unescape(re.search(r'content-desc="([^"]*)"', node).group(1))
    text = html.unescape(re.search(r' text="([^"]*)"', node).group(1))
    if (label in desc or label in text) if contains else (desc.startswith(label) or text.startswith(label)):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$xy" ] && break
  sleep 2
  done
  echo "$xy"
}
tap_label() {
  local xy
  xy=$(find_label "$@")
  if [ -n "$xy" ]; then adb shell input tap $xy; else echo "no control labelled '$1'" | tee -a "$OUT/tts.txt"; fi
  sleep 2
}
adb shell am force-stop $PKG
printf '[{"id":"test","title":"Crime and Punishment","author":"Fyodor Dostoyevsky","fileName":"test.epub","coverFileName":null,"addedAt":1,"lastOpenedAt":0,"progress":0,"chapterIndex":3,"chapterOffset":0}]' \
  | adb exec-in run-as $PKG sh -c 'cat > files/library.json'
adb shell am start -W -n $PKG/.MainActivity --es open_book test
sleep 8
swipe_next
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
tap_label "Bookmark this page"
adb exec-out screencap -p > "$OUT/8-bookmark-added.png"
tap_label "Read aloud"
sleep 4
adb exec-out screencap -p > "$OUT/9-reading-aloud.png"
sleep 8
adb exec-out screencap -p > "$OUT/9-reading-aloud-later.png"
tap_label "Voice and sleep timer"
adb exec-out screencap -p > "$OUT/9-read-aloud-options.png"
tap_label "30 min"
adb shell input keyevent KEYCODE_BACK; sleep 2
adb exec-out screencap -p > "$OUT/9-sleep-timer.png"
tap_label "Stop reading aloud"
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
tap_label "Contents"
tap_label "Bookmarks"
adb exec-out screencap -p > "$OUT/10-bookmarks-list.png"
adb shell input keyevent KEYCODE_BACK; sleep 1

# Highlights, notes and the dictionary: press and hold a word, then use the menu.
pip install --quiet pillow || true
# Taps item N (0 = Highlight, 1 = Note, 2 = Define, 3 = Copy) of the selection menu.
tap_menu() {
  adb exec-out screencap -p > menu.png; cp menu.png "$OUT/menu-$1.png"
  local xy
  xy=$(python3 .github/scripts/menu-item.py menu.png "$1")
  if [ -n "$xy" ]; then adb shell input tap $xy; else echo "no selection menu item $1" | tee -a "$OUT/tts.txt"; fi
  sleep 2
}
long_press() { adb shell input swipe "$1" "$2" "$1" "$2" 1200; sleep 2; }
adb shell input tap $((W / 2)) $((H / 2)); sleep 1   # hide the controls
long_press $((W * 35 / 100)) $((H / 2))
adb exec-out screencap -p > "$OUT/11-selection-menu.png"
tap_menu 0
adb exec-out screencap -p > "$OUT/12-highlighted.png"
adb shell input tap $((W * 35 / 100)) $((H / 2)); sleep 2
adb exec-out screencap -p > "$OUT/13-highlight-sheet.png"
tap_label "Note"
adb shell input text "A%snote%sabout%sthis"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1   # hide the keyboard
tap_label "Done"
sleep 2
long_press $((W * 55 / 100)) $((H * 62 / 100))
adb exec-out screencap -p > "$OUT/14-define-menu.png"
tap_menu 2
sleep 4
adb exec-out screencap -p > "$OUT/14-define.png"
# Close the dictionary if it opened (a stray Back would leave the book).
[ -n "$(find_label "Search the web")" ] && { adb shell input keyevent KEYCODE_BACK; sleep 2; }

# Search the whole book and open a match.
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
tap_label "Search in book"
adb shell input text "landlady"; sleep 6
adb exec-out screencap -p > "$OUT/15-search.png"
tap_label "·" contains
sleep 4
adb exec-out screencap -p > "$OUT/16-search-result.png"
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
tap_label "Contents"
tap_label "Highlights"
adb exec-out screencap -p > "$OUT/17-highlights-list.png"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell run-as $PKG cat files/library.json > "$OUT/library.json" || true

# Library: reading stats, and putting a book in a collection.
adb shell input keyevent KEYCODE_BACK; sleep 3
tap_label "Reading stats"
adb exec-out screencap -p > "$OUT/18-stats.png"
adb shell input keyevent KEYCODE_BACK; sleep 2
# (Searching hides the "Continue reading" row, so the first match is the book's cover.)
tap_label "Search titles and authors"
adb shell input text "Crime"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
xy=$(find_label "Crime and Punishment")
if [ -n "$xy" ]; then long_press $xy; fi
tap_label "Collections"
adb shell input text "Classics"; sleep 1
tap_label "Save"
adb exec-out screencap -p > "$OUT/19-collection.png"
adb shell run-as $PKG cat files/stats.json > "$OUT/stats.json" || true

adb shell dumpsys package com.svox.pico > /dev/null 2>&1 && echo "pico present" >> "$OUT/tts.txt"
adb shell settings get secure tts_default_synth >> "$OUT/tts.txt" 2>&1 || true

adb logcat -d > "$OUT/logcat.txt"
grep -iE "chromium|console|folio|AndroidRuntime" "$OUT/logcat.txt" > "$OUT/logcat-relevant.txt" || true
exit 0
