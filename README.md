# Folio — an EPUB reader for Android

A clean, fast EPUB reader built with Kotlin, Jetpack Compose and Material 3.

## Features

- **Library** with cover art, a "Continue reading" carousel, search, shelves (Reading, Not started, Finished), sorting, reading progress on every book, and New/Finished badges. Long-press a book to mark it unread or remove it.
- **Import** one or many `.epub` files from your phone, or open/share an EPUB from any app straight into Folio.
- **Reader** that remembers your exact place in every book.
  - Real pages: swipe, tap the left or right side, or press the volume buttons to turn the page (it rolls into the next chapter). Tap the middle to show controls.
  - Full-screen immersive reading, screen stays on while reading.
  - Table of contents, a progress slider to jump anywhere in the book, chapter skip buttons.
  - Text size, bundled reading fonts (Literata, Inter, Atkinson Hyperlegible, or the book's own), line spacing, margins, justified text.
  - Themes: Auto (follows system), Paper, Sepia, Dusk and true-black Night for OLED.
- A warm paper-and-ink Material 3 design with Fraunces headings and Inter text. Dark mode throughout.

Fonts are bundled under the SIL Open Font License; see `app/src/main/assets/fonts/licenses/`.
- Books are read straight from the EPUB file (no unpacking), and anything a book tries to load from the internet is blocked.

Requires Android 8.0 (API 26) or newer.

## Build

Open the folder in Android Studio (Ladybug or newer) and press **Run**, or from a terminal with the Android SDK installed:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. On GitHub, every push builds the same APK and publishes it as `folio-debug.apk` on the **Folio (latest build)** release.

## Layout

| Path | What it is |
| --- | --- |
| `epub/EpubBook.kt` | Dependency-free EPUB 2/3 parser (container, OPF, nav/NCX table of contents, cover). |
| `data/LibraryRepository.kt` | Imported books, covers and reading positions. |
| `data/ReaderSettings.kt` | Reader preferences and themes. |
| `ui/library/` | Library screen. |
| `ui/reader/` | Reader screen, WebView content server, injected CSS/JS. |
