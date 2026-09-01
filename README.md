<img src=".docs/app_icon.png" alt="Moonkata Reader app icon" width="96" height="96">

# Moonkata Reader (문카타 리더)

**[English](README.md) | [한국어](README.ko.md)**

An Android text reader for local `.txt` novels, built solo end-to-end as a **fully offline, single-user app** — no server, no login, no sync.

![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09-4285F4?logo=jetpackcompose&logoColor=white)
![Room](https://img.shields.io/badge/Room-2.7.2-3DDC84?logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey)

## Why I built this

Dedicated e-book/web-novel reader apps often ship fine-grained customization — chapter-jump skimming, downloadable fonts, multiple page-turn styles — that off-the-shelf text viewers don't. I wanted to design and build that whole feature set myself from a blank slate: the DB schema, the pagination algorithm, and offline file handling (SAF, encoding detection, zip browsing), all without leaning on a framework to do the hard parts for me.

## Features

### Library / files
- A tap-to-descend folder browser built on the Storage Access Framework (SAF) — not a recursive full-tree scan — with sort by name/date/size
- Automatic encoding detection for UTF-8 / EUC-KR / CP949 (via `juniversalchardet`)
- Browses inside `.zip` archives like a folder and opens `.txt` files straight from them
- On launch, offers to resume the last book you were reading

### Reading experience
- Two reading modes: **swipe pagination** and **vertical scroll**
- Page-transition animation: none / slide / cover
- Fine-grained control over font size, line height, letter spacing, and margins
- Light / dark / sepia themes plus an in-app brightness override
- Configurable tap zones, swipe direction, and volume-key mapping for page turns
- **Free Korean font downloads** — pick an OFL-licensed Korean font from a built-in catalog, download it, and apply it immediately

### Progress tracking
- **Character-offset-based resume** — reopens at the exact spot even if font/margin changes shifted every page boundary
- Regex-based automatic chapter (table of contents) detection, with built-in presets you can toggle and custom patterns you can add
- Opening the table of contents auto-scrolls to and highlights the chapter you're currently reading
- **Chapter-jump mode** — splits the current chapter into N equal parts and jumps through them in order, for fast skimming

### Convenience
- In-book search — runs only on explicit submit (search button or IME action), not while typing; keeps your last query/results between sheet opens, and highlights the result nearest your current position
- Line-break handling options (preserve original / reflow into paragraphs)
- Screen-on lock and orientation lock
- Timer-based auto page-turning or TTS narration (mutually exclusive)

## Tech stack

| Area | Tech |
|---|---|
| Language | Kotlin 2.2.0 |
| UI | Jetpack Compose (BOM 2024.09.00) + Material3 + Navigation-Compose |
| Async | Kotlin Coroutines, background pagination on `Dispatchers.Default` |
| Local DB | Room 2.7.2 (KSP) |
| Settings storage | DataStore Preferences |
| File handling | Storage Access Framework, `java.util.zip`, `juniversalchardet` |
| Speech | Android `TextToSpeech` |
| Architecture | Manual MVVM (`AndroidViewModel` + Repository), no DI framework |

## Design decisions worth calling out

**Reading position is stored as a character offset into the full text, not a page number.** Font size, margins, and screen size all change how the text paginates, so page indices are always treated as a derived value recomputed on the fly — only the offset is persisted in Room (`BookEntity`).

**Page mode never precomputes a full page list for the book.** Paginating a multi-hundred-thousand-character novel up front is slow, and keeping a precomputed list in sync with whatever's on screen invites drift. Instead, only the page currently on screen (a character range) is computed at any time — the next page is measured fresh from the current page's end offset, and the previous page either pops off a forward-navigation history stack (exact, instant) or, when there's no history (e.g. right after a search jump), is estimated by measuring forward from a point behind it. Page transitions are driven by `AnimatedContent`, not `HorizontalPager`'s index-based scrolling, so there's never a page count/index to keep synchronized.

**Chapters (table of contents) aren't stored in the database — they're recomputed with regex every session.** This means new detection patterns can be added or tuned without a schema migration, and zero matches is handled as a normal "no table of contents" state rather than an error.

**Auto-advance is modeled as a single three-way state: `OFF` / `TIMER` / `TTS`.** Two independent booleans would allow both to be enabled at once; a single state rules that conflict out from the start.

## Project structure

```
com.moonkata.textreader/
├── MainActivity.kt              — NavHost, delegates physical-key events only
├── navigation/                  — library ↔ reader screen transitions
├── data/
│   ├── db/                      — Room: BookEntity, DAO
│   ├── datastore/                — ReaderSettings, ReaderSettingsRepository
│   ├── file/                     — SAF folder browser, encoding detection, BookSource (zip support)
│   ├── font/                     — Korean font catalog + download manager
│   ├── parser/                   — TextReflower, ChapterDetector/ChapterPatternCatalog, Paginator, ChapterJumpNavigator
│   └── repository/               — BookRepository
├── model/                        — Paragraph, Chapter, PageBreak, FolderEntry, etc.
├── ui/
│   ├── library/                  — folder browser screen, "resume reading" dialog
│   ├── reader/                   — reader screen, quick settings / TOC / search / font / chapter-pattern sheets
│   └── theme/                    — theme presets
├── tts/                          — TtsController, AutoPageTurnController
└── util/                         — SAF / collection extension functions
```

A file-by-file breakdown of exactly which files implement which feature, and how, lives in [`docs/FEATURES.md`](.docs/FEATURES.md).

## Testing

Tests are split into two source sets based on whether they need the Android runtime.

- **`app/src/test`** — plain JUnit tests for pure logic (paragraph reflow, chapter detection, encoding detection, pagination helper math) that run on the JVM alone, no device/emulator needed.
- **`app/src/androidTest`** — instrumented tests needing Compose rendering, Room/DataStore, or real text measurement. These verify pagination history/round-trips, chapter auto-detection, and encoding detection against real novel fixtures, plus real interaction tests for the library/settings/search/TOC sheets.
- Font downloads are covered both by `MockWebServer`-based tests (success/failure logic against a fake local server) and by real-network tests that confirm the actual OFL font sources (GitHub, etc.) are still reachable and that applying a downloaded font actually changes the viewer — this real-network suite has already caught three font source URLs that had silently broken.
- Platform behavior that Compose's semantics tree can't reliably assert on (IME visibility, real timer/TTS timing) is deliberately left out of the automated suite and verified manually — a test that passes without catching real regressions isn't worth writing.

The full test plan, what each test is meant to verify, and what was deliberately left out, is tracked step by step in [`TESTING.md`](.docs/TESTING.md).

## Build & run

```bash
git clone <this-repo>
cd android-text-reader
./gradlew assembleDebug
```

- `compileSdk` / `targetSdk` 36, `minSdk` 24, Java 11
- Open in Android Studio and run directly on a device or emulator
- Internet access is only needed for the font-download feature — everything else works fully offline

## Roadmap

Future ideas are tracked in [`IDEAS.md`](.docs/IDEAS.md).

## License

[Apache License 2.0](LICENSE)
