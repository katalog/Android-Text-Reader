<img src=".docs/app_icon.png" alt="Moonkata Reader app icon" width="96" height="96">

# Moonkata Reader (문카타 리더)

**[English](README.md) | [한국어](README.ko.md)**

An Android text reader for local `.txt` novels, built solo end-to-end as a **fully offline-first, single-user app** — no accounts, no vendor lock-in, no background telemetry. The core reading experience never touches the network. Two opt-in, off-by-default features let it talk to a PC when you want that: sharing reading position with a VSCode extension, and pulling book files from a small companion PC server — both covered below.

This is the Android app half of the [moonkata-reader-project](https://github.com/katalog/moonkata-reader-project) umbrella — the two PC-side companions it talks to, [go-moonkata-reader-sync-server](https://github.com/katalog/go-moonkata-reader-sync-server) and [vscode-moonkata-reader-sync](https://github.com/katalog/vscode-moonkata-reader-sync), live in their own repos.

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
- The library screen's top bar gives direct access to PC sync, sort, and app settings — no need to open a book first

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

### Cross-device sync (optional, off by default)
- **Reading-position sync with VSCode** — if you also read the same `.txt` files through a companion VSCode extension on a PC, whichever device read further nudges the other to catch up. No accounts: both sides need one shared-secret string, which you either paste in manually or pair instantly by scanning a QR code the other side shows (Android's camera scanner reuses the same flow for both sync features below). A small Supabase project relays just the offset — its access policy is the real gate, not a login system, and every installed app shares that one project, but a server-side trigger hashes each secret into its own `user_key` so installs can never see each other's rows. Best-effort by design — any failure (offline, unverified secret) is silently skipped and never blocks local reading/saving.
- **File sync from a PC** — a small open-source Windows tray app you run on your PC ([go-moonkata-reader-sync-server](https://github.com/katalog/go-moonkata-reader-sync-server), a separate repo, plain Go, no install beyond the exe) shares a folder over HTTPS on your LAN; the Android app mirrors it into your library folder one-way (PC → phone) with a "Sync now" button. No cloud storage, no account — the PC serves as the server directly, authenticated by a secret you copy over once, or by scanning a QR code the tray app can show (bundles host, secret, and TLS fingerprint in one scan, skipping manual entry entirely). The self-signed TLS certificate is trust-pinned SSH-style (trust-on-first-use) rather than CA-verified, since private LAN IPs can't get a real certificate. All tray notifications are non-blocking Windows toasts — the server never sits waiting on a modal dialog you have to click through.

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
│   ├── sync/                     — optional cross-device sync: VSCode reading-position client (Supabase),
│   │                               PC file-sync client (HTTPS + TLS fingerprint pinning), and the shared
│   │                               QR pairing payload both scan flows parse
│   └── repository/               — BookRepository
├── model/                        — Paragraph, Chapter, PageBreak, FolderEntry, etc.
├── ui/
│   ├── library/                  — folder browser screen, "resume reading" dialog, PC sync sheet
│   ├── reader/                   — reader screen, quick settings / TOC / search / font / chapter-pattern sheets
│   ├── qr/                       — camera QR scanner shared by both sync pairing flows
│   ├── theme/                    — theme presets
│   └── SettingsController.kt     — interface the reader and library screens both implement, so the same
│                                   settings sheets can be opened from either one
├── tts/                          — TtsController, AutoPageTurnController
└── util/                         — SAF / collection extension functions
```

The companion PC tray app (Go, no framework) for the file-sync feature above — including its own QR-pairing page and non-blocking toast notifications — lives in a separate repo: [go-moonkata-reader-sync-server](https://github.com/katalog/go-moonkata-reader-sync-server).

A file-by-file breakdown of exactly which files implement which feature, and how, lives in [`docs/FEATURES.md`](.docs/FEATURES.md). A step-by-step trace of which file/function runs for each user action lives in [`docs/USER_SCENARIOS.md`](.docs/USER_SCENARIOS.md).

## Testing

Tests are split into two source sets based on whether they need the Android runtime.

- **`app/src/test`** — plain JUnit tests for pure logic (paragraph reflow, chapter detection, encoding detection, pagination helper math) that run on the JVM alone, no device/emulator needed.
- **`app/src/androidTest`** — instrumented tests needing Compose rendering, Room/DataStore, or real text measurement. These verify pagination history/round-trips, chapter auto-detection, and encoding detection against real novel fixtures, plus real interaction tests for the library/settings/search/TOC sheets.
- Font downloads are covered both by `MockWebServer`-based tests (success/failure logic against a fake local server) and by real-network tests that confirm the actual OFL font sources (GitHub, etc.) are still reachable and that applying a downloaded font actually changes the viewer — this real-network suite has already caught three font source URLs that had silently broken.
- The two cross-device sync features are covered by pure-logic tests (relative-path normalization, the PC-sync delta calculation, TLS fingerprint hashing) plus `MockWebServer`-based protocol tests against both clients — including a real TLS handshake for the HTTPS/fingerprint-pinning client, using an on-the-fly self-signed certificate. What's left to manual real-device verification (actual SAF file writes, LAN subnet scanning, the PC tray app itself) is documented in `TESTING.md`. The Go PC server's path-traversal-prevention logic has its own `go test` suite in its own repo, [go-moonkata-reader-sync-server](https://github.com/katalog/go-moonkata-reader-sync-server).
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

Prebuilt APKs are published automatically on the [Releases](../../releases) page whenever a `vX.Y.Z` tag is pushed — see [`.github/workflows/release.yml`](.github/workflows/release.yml). Release builds are signed with the debug keystore (this project isn't distributed through the Play Store), so `assembleRelease` produces an installable APK with no extra signing setup.

The optional PC file-sync companion now lives in its own repo, [go-moonkata-reader-sync-server](https://github.com/katalog/go-moonkata-reader-sync-server) — a standalone Go module with no runtime dependency on the Android app, released the same way (`vX.Y.Z` tag → prebuilt Windows `.exe` on its own Releases page).

## Roadmap

Future ideas are tracked in [`IDEAS.md`](.docs/IDEAS.md).

## License

[Apache License 2.0](LICENSE)
