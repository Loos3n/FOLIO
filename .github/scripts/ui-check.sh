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
label = sys.argv[1]
contains = sys.argv[2] == 'contains'
nth = int(sys.argv[2][4:]) if sys.argv[2].startswith('nth:') else 0
hits = []
xml = open('ui.xml', encoding='utf-8', errors='ignore').read()
for node in re.findall(r'<node [^>]*>', xml):
    desc = html.unescape(re.search(r'content-desc="([^"]*)"', node).group(1))
    text = html.unescape(re.search(r' text="([^"]*)"', node).group(1))
    if (label in desc or label in text) if contains else (desc.startswith(label) or text.startswith(label)):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if -len(hits) <= nth < len(hits):
    print(*hits[nth])
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
long_press $((W * 35 / 100)) $((H / 2))
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
adb shell input keyevent KEYCODE_BACK; sleep 1   # hide the keyboard
tap_label "·" contains
sleep 4
adb exec-out screencap -p > "$OUT/16-search-result.png"
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
tap_label "Contents"
tap_label "Highlights"
adb exec-out screencap -p > "$OUT/17-highlights-list.png"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell run-as $PKG cat files/library.json > "$OUT/library.json" || true

# Scrolling mode: switch with one tap, scroll, the reading ruler, auto-scroll, and back.
# Shows the controls (a centre tap toggles them) until the given control is on screen.
controls_for() { [ -n "$(find_label "$1")" ] || { adb shell input tap $((W / 2)) $((H / 2)); sleep 2; }; }
controls_for "Switch to scrolling"
tap_label "Switch to scrolling"
sleep 3
adb exec-out screencap -p > "$OUT/20-scroll.png"
adb shell input tap $((W / 2)) $((H / 2)); sleep 2   # hide the controls
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 400; sleep 2
adb exec-out screencap -p > "$OUT/21-scrolled.png"
controls_for "Display"
adb exec-out screencap -p > "$OUT/22-scroll-controls.png"
tap_label "Display"
tap_label "Reading ruler"
adb exec-out screencap -p > "$OUT/23-display-layout.png"
adb shell input keyevent KEYCODE_BACK; sleep 2
controls_for "Auto-scroll"
tap_label "Auto-scroll"
sleep 6
adb exec-out screencap -p > "$OUT/24-autoscroll.png"
tap_label "Stop auto-scroll"
# Fling through to the end of the chapter: the next one should follow on.
for i in $(seq 1 40); do adb shell input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 5)) 60; done
sleep 3
adb exec-out screencap -p > "$OUT/25-scroll-far.png"
controls_for "Switch to pages"
tap_label "Switch to pages"
sleep 3
adb exec-out screencap -p > "$OUT/26-pages-again.png"
adb shell run-as $PKG cat files/library.json > "$OUT/library-scroll.json" || true
adb shell run-as $PKG cat shared_prefs/reader_settings.xml > "$OUT/settings.xml" || true

# Library: reading stats, and putting a book in a collection.
adb shell input keyevent KEYCODE_BACK; sleep 3
tap_label "Reading stats"
adb exec-out screencap -p > "$OUT/18-stats.png"
adb shell input keyevent KEYCODE_BACK; sleep 2
# The title appears on the "Continue reading" card and on the grid tile's cover and caption;
# the second-to-last is the tile's cover, which is what opens the menu.
tap_label "All"
xy=$(find_label "Crime and Punishment" nth:-2)
if [ -n "$xy" ]; then long_press $xy; fi
tap_label "Collections"
tap_label "New collection"
adb shell input text "Classics"; sleep 1
tap_label "Save"
adb exec-out screencap -p > "$OUT/19-collection.png"
adb shell run-as $PKG cat files/stats.json > "$OUT/stats.json" || true

# Audiobooks: an M4B with chapters and cover art, imported as if picked, then played.
command -v ffmpeg > /dev/null || { sudo apt-get update -q && sudo apt-get install -y -q ffmpeg; }
cat > chapters.txt <<'META'
;FFMETADATA1
title=The Test Audiobook
artist=Ada Author
album=The Test Audiobook
[CHAPTER]
TIMEBASE=1/1000
START=0
END=20000
title=Opening Credits
[CHAPTER]
TIMEBASE=1/1000
START=20000
END=200000
title=Chapter One: The Start
[CHAPTER]
TIMEBASE=1/1000
START=200000
END=400000
title=Chapter Two: The Middle
[CHAPTER]
TIMEBASE=1/1000
START=400000
END=600000
title=Chapter Three: The End
META
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=330:duration=600" -f lavfi -i "color=c=0x2F4858:s=600x600,drawbox=x=60:y=60:w=480:h=480:color=0xF6AE2D:t=8" -frames:v 1 cover.png
ffmpeg -loglevel error -y -i cover.png -f lavfi -i "sine=frequency=330:duration=600" -i chapters.txt \
  -map 1:a -map 0:v -map_metadata 2 -map_chapters 2 -c:a aac -b:a 32k -c:v copy -disposition:v attached_pic book.m4b \
  || ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=330:duration=600" -i chapters.txt -map_metadata 1 -map_chapters 1 -c:a aac -b:a 32k book.m4b
ls -la book.m4b
adb push book.m4b /data/local/tmp/book.m4b
adb shell chmod 644 /data/local/tmp/book.m4b
adb shell run-as $PKG cp /data/local/tmp/book.m4b files/book.m4b
adb shell am start -W -n $PKG/.MainActivity --es import_file book.m4b
sleep 6
adb exec-out screencap -p > "$OUT/30-audiobook.png"
tap_label "Play"
sleep 6
adb exec-out screencap -p > "$OUT/31-playing.png"
tap_label "Forward 30 seconds"
sleep 2
adb exec-out screencap -p > "$OUT/32-after-skip.png"
tap_label "Chapters"
adb exec-out screencap -p > "$OUT/33-chapters.png"
tap_label "Chapter Two: The Middle"
sleep 2
tap_label "Speed"
tap_label "1.5×"
tap_label "Sleep"
tap_label "End of chapter"
sleep 2
adb exec-out screencap -p > "$OUT/34-speed-sleep.png"
adb shell cmd statusbar expand-notifications; sleep 3
adb exec-out screencap -p > "$OUT/35-notification.png"
adb shell cmd statusbar collapse; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 3
adb exec-out screencap -p > "$OUT/36-library-mini-player.png"
adb shell input keyevent KEYCODE_HOME; sleep 8
adb shell dumpsys media_session > "$OUT/media-session.txt" 2>&1 || true
adb shell run-as $PKG cat files/library.json > "$OUT/library-audio.json" || true

adb shell dumpsys package com.svox.pico > /dev/null 2>&1 && echo "pico present" >> "$OUT/tts.txt"
adb shell settings get secure tts_default_synth >> "$OUT/tts.txt" 2>&1 || true

adb logcat -d > "$OUT/logcat.txt"
grep -iE "chromium|console|folio|AndroidRuntime" "$OUT/logcat.txt" > "$OUT/logcat-relevant.txt" || true
exit 0
