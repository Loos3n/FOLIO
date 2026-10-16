# Folio — an EPUB reader for Android

A clean, fast EPUB reader built with Kotlin, Jetpack Compose and Material 3.

## Features

- **Library** with cover art, a "Continue reading" card, reading progress on every book, and New/Finished badges. Long-press a book to mark it unread or remove it.
- **Import** one or many `.epub` files from your phone, or open/share an EPUB from any app straight into Folio.
- **Reader** that remembers your exact place in every book.
  - Tap the right or left edge to page forward/back (it rolls into the next chapter), tap the middle to show controls.
  - Full-screen immersive reading, screen stays on while reading.
  - Table of contents, a progress slider to jump anywhere in the book, chapter skip buttons.
  - Text size, font (book's own, serif, sans), line spacing, margins, justified text.
  - Themes: Auto (follows system), Light, Sepia, Dark and true Black for OLED.
- Material You dynamic colour on Android 12+, with a hand-tuned palette on older phones. Dark mode throughout.
- Books are read straight from the EPUB file (no unpacking), and anything a book tries to load from the internet is blocked.

Requires Android 8.0 (API 26) or newer.

## Build

Open the folder in Android Studio (Ladybug or newer) and press **Run**, or from a terminal with the Android SDK installed:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. If this project is pushed to GitHub, the included workflow builds the same APK on every push and attaches it to the run.

## Layout

| Path | What it is |
| --- | --- |
| `epub/EpubBook.kt` | Dependency-free EPUB 2/3 parser (container, OPF, nav/NCX table of contents, cover). |
| `data/LibraryRepository.kt` | Imported books, covers and reading positions. |
| `data/ReaderSettings.kt` | Reader preferences and themes. |
| `ui/library/` | Library screen. |
| `ui/reader/` | Reader screen, WebView content server, injected CSS/JS. |
