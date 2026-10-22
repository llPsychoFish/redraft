# Redraft

An Android PDF editor that performs **true content editing** — changing existing text and
images inside a PDF — rather than just annotating it. Pages are rendered through PDFium
(via NDK/JNI) and edited at the page-object level, regenerating the content stream on save.

> **Status: v0.1.2 — viewer only.** Redraft opens, renders, zooms, pans and navigates
> documents, with a branded theme, a working Settings screen, and honest About/Support pages.
> The editing features — selecting an object, changing its text, moving an image, writing the
> file back — are Phases 2 through 5 and are **not implemented yet**. The About screen says
> this in the app too: *"Viewer today; true content editing is in development."* See
> [Roadmap](#roadmap).

---

## What it does today

| | |
|---|---|
| **Open** | Any PDF through the Storage Access Framework, with the permission persisted so it reopens without the picker |
| **Recents** | An MRU list of recently opened files, deduped, capped at 10, persisted in DataStore |
| **Render** | Lazy paging through PDFium — only the visible pages are composed and rendered |
| **Zoom** | Pinch-to-zoom that zooms the page *in place*, under your fingers, with the content re-anchored on the pinch focal point every frame |
| **Pan** | A zoomed page pans sideways to either end; one-finger horizontal drags pan, vertical drags page |
| **Navigate** | A page overview — a lazy grid of page thumbnails — so you can see the shape of a document and jump to any page |
| **Survive rotation** | Zoom, pan and scroll position all survive a rotation or a configuration change |
| **Degrade gracefully** | Corrupt, password-protected and inaccessible files produce a readable error and return you to Home — no crash, no blank viewer |
| **Stay bounded** | A byte-sized LRU bitmap cache with reference-counted recycling. Measured flat at ~115 MB total / ~21 MB of bitmaps while scrolling a 120-page document |
| **Stay off the main thread** | Every PDFium call is guarded by an assertion; StrictMode is on in debug builds and reports nothing |

### What it does *not* do yet

Selecting objects, editing text, editing images, and saving. Those are the whole point of the
project and they are the next four phases.

---

## Screenshots

No screenshots are committed yet — the images would otherwise ship before the app has been
visually reviewed on a device. When they exist, they belong in `docs/screenshots/` at 1080 px
wide, captured on a phone with the default (system) theme, and linked here as:

| Home | Viewer | Settings |
|---|---|---|
| `docs/screenshots/home.png` | `docs/screenshots/viewer.png` | `docs/screenshots/settings.png` |

Capture them with `adb exec-out screencap -p > docs/screenshots/<name>.png`.

---

## Tech stack

| | |
|---|---|
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose (BOM 2026.09.00), Material 3 |
| Typography | Inter font (TTF, bundled) |
| PDF engine | [pdfiumandroid](https://github.com/johngray1965/PdfiumAndroidKt) 3.0.4-alpha01 (PDFium via NDK/JNI) |
| Build | AGP 9.4.0, Gradle 9.6.0, JDK 17 toolchain |
| Platform | minSdk 26, targetSdk 37, ABIs `arm64-v8a` + `x86_64` |
| Async | Coroutines 1.9.0, DataStore Preferences 1.2.1, Navigation Compose 2.10.2 |
| Tests | JUnit 4 — 88 unit tests, no Robolectric (the logic is kept Android-free) |

Two Gradle modules: `app` (UI, state, navigation) and `pdfcore` (the PDFium wrapper, the
coordinate transform, the handle registry). The split keeps the native engine out of the UI
module and makes the pure logic unit-testable on the JVM.

---

## Getting started

### Requirements

- **JDK 17** (the `pdfcore` module pins `jvmToolchain(17)`)
- Android SDK with **compileSdk 37**
- The Gradle wrapper handles the rest — no local Gradle install needed
- A device or emulator on API 26+ (x86_64 for the emulator)

### Build and test

```bash
./gradlew :app:assembleDebug          # build the APK
./gradlew :app:testDebugUnitTest       # run the unit tests
./gradlew :app:assembleDebug :app:testDebugUnitTest   # both
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. R8 shrinking is on, and its keep rules for
the PDFium JNI binding live in `app/proguard-rules.pro`; the mapping file is written to
`app/build/outputs/mapping/release/mapping.txt` — **keep it**, it is the only way to read a
release stack trace.

Signing material is never committed. The build reads these keys from `local.properties` (or the
same names in the environment), and simply produces an **unsigned** APK when they are absent:

```properties
RELEASE_STORE_FILE=/absolute/path/to/redraft-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=redraft
RELEASE_KEY_PASSWORD=...
```

The keystore must be backed up in **two places** before the first upload: a lost key cannot be
replaced, and a replacement key blocks every future update to installed users.

> `applicationId` is still `com.redraft`. It must be settled before the first publish, because
> changing it afterwards ships a different app (see the TODO in `app/build.gradle.kts`).

### Sample document

`app/src/main/assets/sample.pdf` is generated deterministically — it is not an opaque committed
binary:

```bash
python3 tools/gen_sample_pdf.py
```

---

## Project structure

```
redraft/
├─ app/                                    # UI, state, navigation
│  └─ src/main/kotlin/com/redraft/
│     ├─ MainActivity.kt                   # single activity, edge-to-edge, StrictMode in debug
│     ├─ ui/
│     │  ├─ home/                          # the recents list
│     │  ├─ nav/                           # the NavHost and the routes
│     │  ├─ viewer/                        # the document viewer
│     │  │  ├─ PdfViewerScreen.kt          #   the list, the gesture handlers, the item layout
│     │  │  ├─ PageBitmapCache.kt          #   the byte-bounded LRU
│     │  │  ├─ BitmapRefTracker.kt         #   the two-owner reference counting
│     │  │  ├─ ZoomGestureMath.kt          #   pure two-finger delta math
│     │  │  ├─ ZoomAnchor.kt               #   pure focal-anchor and pan-clamp math
│     │  │  ├─ RenderedPage.kt             #   the bitmap + the bucket it was rendered at
│     │  │  └─ SinglePointerDoubleTap.kt   #   a double tap that a pinch cannot trigger
│     │  └─ overview/                      # the page overview grid
│     ├─ data/                             # SAF, the recents repository, the document handle
│     └─ PdfViewerViewModel.kt             # the viewer state
│
├─ pdfcore/                                # the PDFium wrapper
│  └─ src/main/kotlin/com/redraft/pdfcore/
│     ├─ PdfDocumentHandle.kt              #   a suspend handle over one document
│     ├─ PdfViewportTransform.kt           #   pure screen <-> PDF user-space math
│     ├─ PdfHandleRegistry.kt              #   the leak registry
│     └─ PdfCoreDispatchers.kt             #   the off-main-thread guard
│
├─ tools/gen_sample_pdf.py                 # the deterministic sample generator
└─ .planning/                              # the project's plans, roadmap and verification record
```

---

## Architecture notes

A few decisions that are not obvious from the code alone.

**The bitmap cache counts two owners, not one.** A page bitmap can be held by the cache (it is
in an LRU slot) *and* by the drawer (it is on screen). Recycling it while either still holds it
is a use-after-free that crashes the draw pass with `Canvas: trying to use a recycled bitmap`.
`BitmapRefTracker` counts both claims independently and the free is deferred by two frames,
because the frame that last drew the bitmap may still be in flight on the render thread.

**The gesture math is pure and lives outside the composable.** `ZoomGestureMath` and
`ZoomAnchor` contain no Android and no Compose imports, so the rules that decide how a pinch
behaves are unit-testable on the JVM. The same pattern is used for `PdfViewportTransform` in
`pdfcore`, which Phase 2 reuses verbatim for hit-testing.

**The pinch is a hand-rolled multi-touch handler, not `detectTransformGestures`.** The
LazyColumn's own scroll detector is innermost in the pointer dispatch order, so it receives the
second pointer-down first and swallows it — `detectTransformGestures` never sees two pointers
and pinch-zoom silently does nothing. The handler inspects `event.changes` itself: one pointer
means the events are left *unconsumed* so the list keeps scrolling, two or more means it takes
over.

**The zoom is a transform over a page, not a re-layout of the page.** The page's slot follows
the live zoom, so the drawn page always exactly fills its slot, and the bitmap is scaled by a
single factor on both axes — an unequal pair is exactly the aspect-ratio stretch the UAT caught
during development.

**Only an explicit command may move the list.** The ViewModel holds a *mirror* of the scroll
position, and a one-shot, token-stamped `ScrollCommand` for actually moving it. A mirror must
never drive the list: during a fling the mirror is a frame behind, and restoring from it cancels
the fling. (That bug cost three plans to find — the symptom was "a hard flick only moves one
page".)

---

## Roadmap

| Phase | Name | Status |
|---|---|---|
| 1 | Foundation & Viewer | **Done** — 14/14 plans, UAT 19/19, verification passed |
| 01.1 | UI, Settings & Release Prep | **Done** — v0.1.0: brand theme, Settings, About/Support, adaptive icon, signed-release configuration |
| **01.2** | **Polish & Performance** | **Done** — v0.1.2: navigation transition fix, dark mode contrast, Inter font bundled |
| 2 | Selection & Hit-Testing | Next — a tap reliably selects the object you meant |
| 3 | Text Editing MVP | Planned — inline editing of a selected text object |
| 4 | Image & Object Editing | Planned — select, move, resize, delete, replace |
| 5 | Save & Hardening | Planned — write the edited document back out |

The full plan, requirement and decision history lives in `.planning/`.

---

## Recent improvements (v0.1.2)

- **Navigation transitions**: Fixed back-gesture glitch where screens overlapped and jumped. `popExitTransition` now slides out to the right (mirroring `popEnter`), and all transition durations reduced from 300 ms → 250 ms to minimize overlap while the heavy viewer screen composes.
- **Dark/AMOLED contrast**: About screen body text uses `onSurface` (full opacity), feature chips use `primaryContainer`/`onPrimaryContainer` (amber on dark), and the Support button uses `primary`/`onPrimary` — all readable in Light, Dark, and AMOLED themes.
- **Typography**: Genuine Inter TTF fonts (Regular, Medium, SemiBold, Bold) bundled via `res/font/` and wired into `RedraftTypography`.

---

## Known issues

- **Page rotation does not reach the coordinate transform.** `PdfDocumentHandle.pageSize` stores
  PDFium's `getPageRotation()` — an *index* 0..3, not degrees — in a field named
  `rotationDegrees`, so `PdfViewportTransform` collapses it to 0 and its 90/270 arms are
  unreachable. There is no Phase 1 user-visible impact (PDFium's width and height are already
  rotated, so pages display correctly), but it lands directly on Phase 2's hit-testing. Fix it at
  the source, and do **not** swap the dimensions twice.
- **Scrolling a zoomed document janks.** About 19% of frames exceed their deadline, all of it
  UI-thread work — the GPU is fine (1 ms median). The cause is the cache budget: it is
  `maxMemory / 8` (32 MB on a 256 MB heap) while a single page bitmap is 23 MB at 3× and 64 MB
  at 5×, so a zoomed document re-renders pages instead of reusing them.
- **Encrypted and password-protected PDFs are not supported.** They fail with a readable error
  rather than opening.
- **Font subsetting.** PDFs frequently embed subset fonts containing only the characters
  already used, so new characters may have no glyph available. This affects Phase 3.

---

## Development workflow

This project is developed with the GSD planning workflow (see `AGENTS.md` for the rules).
Work moves `discuss → plan → execute → verify`, and every decision is recorded:

- `.planning/PROJECT.md` — what the project is, the core value, the key decisions
- `.planning/ROADMAP.md` — the phases, their goals and their success criteria
- `.planning/STATE.md` — where the work is right now
- `.planning/phases/NN-*/` — each phase's plans, the code they produced, and its UAT record

`.planning/` is not tracked by git. `AGENTS.md` holds the project conventions, the stack
rationale and the workflow rules.

---

## License

MIT — see [LICENSE](LICENSE). The same text ships inside the app at `res/raw/licence.txt` and is
shown in Settings → About → Licence.

PDFium's own `LICENSE` carries the BSD-3-Clause text for PDFium code and the Apache 2.0 terms for
its bundled parts; `io.legere:pdfiumandroid`, Kotlin, kotlinx.coroutines and the AndroidX libraries
declare Apache 2.0 in their published Maven metadata. Settings → About lists them, with each
licence checked against the resolved artifact rather than copied from memory.
