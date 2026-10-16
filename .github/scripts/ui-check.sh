#!/usr/bin/env bash
# Runs the debug APK on an emulator with a real Project Gutenberg book and captures
# screenshots of the library and reader, so layout bugs show up without a phone.
set -x
PKG=com.folio.reader
OUT=shots
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size > "$OUT/screen.txt"
read -r W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
W=${W:-1080}; H=${H:-2400}

curl -fsSL --retry 3 -o book.epub https://www.gutenberg.org/cache/epub/2554/pg2554-images-3.epub || exit 1
adb push book.epub /data/local/tmp/book.epub

# First launch creates the app's data directory.
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

adb logcat -d > "$OUT/logcat.txt"
grep -iE "chromium|console|folio|AndroidRuntime" "$OUT/logcat.txt" > "$OUT/logcat-relevant.txt" || true
exit 0
