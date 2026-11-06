# Roadmap: Redraft

## Overview

Redraft ships as five vertical MVP phases: first a working SAF-opened PDFium viewer with safe native I/O, then tap-to-select with correct coordinate mapping and fragmented-run merging, then the core value — inline text editing with font-subset fallback and an undo/redo command stack from day one — then image and object manipulation, and finally save-as-copy with content-stream regeneration validated by re-render plus graceful error handling. Each phase ends with a usable app; nothing depends on a big-bang integration at the end.

**Inserted detour — Phase 01.1 (UI, Settings & Release Prep)** sits between Phase 1 and Phase 2: a time-boxed pass that gives the app a real brand identity, working settings/About/Support screens, and a signed v0.1.0 release, so the app is presentable and installable before editing work starts. It builds only on what works today and must not grow — Phase 2 (selection and editing) is still the core value.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: Foundation & Viewer** - Project scaffold, PDFium integration, SAF open + recent files, lazy-paged Compose viewer with pinch-zoom and LRU bitmap cache
- [x] **Phase 01.1: UI, Settings & Release Prep** (INSERTED) - Branded theme, settings/About/Support screens, app identity, signed v0.1.0 release (detour before Phase 2) (completed 2026-10-25)
- [ ] **Phase 2: Selection & Hit-Testing** - Tap resolves to real PDFium objects; bbox overlays; logical block merging for fragmented text runs
- [ ] **Phase 3: Text Editing MVP** - Inline editor, commit + re-render, undo/redo command stack, font-subset fallback (the core value)
- [ ] **Phase 4: Image & Object Editing** - Image select/move/resize/delete/replace; text move/delete
- [ ] **Phase 5: Save & Hardening** - Save-as-copy with content-stream regeneration, undo-stack checkpointing, graceful error handling

## Phase Details

### Phase 1: Foundation & Viewer

**Goal**: As a reader with a PDF on their device, I want to open it from storage and browse it smoothly, so that the rendering foundation every later editing feature stands on is solid.
**Mode**: mvp
**Depends on**: Nothing (first phase)
**Requirements**: [VIEWER-01, VIEWER-02, VIEWER-03, VIEWER-04, VIEWER-05, VIEWER-06, VIEWER-07]
**Success Criteria** (what must be TRUE):

  1. User can open a PDF via the system file picker and see it in a recent-files list on relaunch
  2. User can scroll through pages lazily, pinch-zoom and pan, and rotate the device without losing zoom/pan state
  3. Memory stays bounded on large documents (LRU bitmap cache; no OOM after browsing a 100+ page PDF)
  4. StrictMode/native checks show no main-thread PDFium calls and no leaked document/page handles

**Plans**: 14/14 plans executed

Plans:

- [x] 01-01-PLAN.md
- [x] 01-02-PLAN.md
- [x] 01-03-PLAN.md
- [x] 01-04-PLAN.md
- [x] 01-05-PLAN.md
- [x] 01-06-PLAN.md
- [x] 01-07-PLAN.md
- [x] 01-08-PLAN.md
- [x] 01-09-PLAN.md
- [x] 01-10-PLAN.md
- [x] 01-11-PLAN.md
- [x] 01-12-PLAN.md
- [x] 01-13-PLAN.md
- [x] 01-14-PLAN.md

**Wave 1**

- [x] 01-01: Project scaffold — Kotlin/Compose modules (app + pdfcore), PDFium binding integration, coroutine I/O dispatchers

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 01-02: SAF open + recent-files persistence + document handle lifecycle (open/close discipline)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 01-03: Compose page viewer — lazy paging, pinch-zoom/pan, LRU bitmap cache with recycling, screen↔PDF coordinate transforms

**Wave 4** *(gap closure — blocked on Wave 3 completion)*

- [x] 01-04: Pinch-zoom fix — replace starved `detectTransformGestures` with a custom `awaitEachGesture` multi-touch handler (zoom + pan), pure gesture math, rotation-safe pan state (G-01-5)
- [x] 01-05: Rendered-bucket decoupling — track the bucket the bitmap was actually rendered at separately from the live zoom, so a bucket crossing never blanks the page and the scale stays one uniform factor
- [x] 01-06: Live-zoom slot + focal anchoring — the page slot follows the live zoom, the scroll is re-anchored on the pinch focal point every frame, the pan is clamped and the vertical pan removed, and the double tap can no longer fire on a multi-touch gesture
- [x] 01-07: One-finger horizontal pan — a zoomed page can be swiped sideways to reach the content on its far end
- [x] 01-08: Symmetric pan bounds — the page is centred in the viewport and overflows it equally on both sides, so the pan reaches each end and no further
- [x] 01-09: Edge release — at a pan boundary the gesture is released instead of swallowed, so the list scroll can take it back
- [x] 01-10: Forgiving double tap — the two taps no longer have to land within the 8 dp touch slop, so a double tap is recognised on the first attempt
- [x] 01-11: Scroll command channel — the scroll-restore effect no longer reacts to the mirrored scroll position, so it can no longer cancel a fling; a hard flick now scrolls through several pages
- [x] 01-12: Page overview — a lazy grid of page thumbnails reached from the viewer's app bar, jumping to the tapped page (a bookmark TOC was rejected: none of the user's PDFs has an outline)
- [x] 01-13: Consume the scroll command — a command is applied exactly once, so the position survives a rotation or a return from the overview on any page, not just the first
- [x] 01-14: One selection per overview entry — a double tap on a thumbnail no longer pops the navigation twice and closes the document

### Phase 01.1: UI, Settings & Release Prep (INSERTED)

**Goal**: As someone using Redraft today, I want the app to look and feel like a real product — a branded theme, working settings, honest About/Support pages — and to be installable as a signed v0.1.0 release, so that it is presentable and shippable before the editing work starts.
**Mode**: mvp
**Depends on**: Phase 1
**Source**: `docs/UI_AND_RELEASE_PLAN.md` (the work packages below are derived from it — treat it as the authority for scope and ordering)
**Requirements**: [UI-01, UI-02, UI-03, UI-04, RELEASE-01, RELEASE-02, RELEASE-03, RELEASE-04]
**Success Criteria** (what must be TRUE):

  1. Theme switching works instantly and survives restarting the app; there is no white flash on launch in dark mode
  2. Settings, About, and Support screens are reachable from Home and every link on them works
  3. The app ships without the `INTERNET` permission and works fully offline, and the About page's privacy statement matches actual behaviour
  4. A release APK builds, is signed with the developer's own key, and passes the manual test checklist on a real device (open, render, zoom, overview, settings)
  5. GitHub Release `v0.1.0` is published with the APK and its SHA-256 checksum, and the Orion Store submission is sent
  6. The existing unit tests still pass (`./gradlew :app:testDebugUnitTest`) and the new settings logic has its own tests

**Guardrails** (from the source doc):

- **Only build what works today.** No setting or UI for editing features that do not exist yet — this detour runs before Phase 2.
- **No `INTERNET` permission** for as long as possible. It is a strong privacy story and it keeps the About page's claims honest; if an update check or crash reporting is ever added, the privacy text changes in the same commit.
- **Time-box this phase.** When it is done, return to Phase 2 (selection and editing) — that is what makes Redraft different.
- Donations are external links only (no billing library, no `INTERNET` permission) and never gate a feature.

**Plans**: 8/8 plans complete

Plans:

- [x] 01.1-01-PLAN.md
- [x] 01.1-02-PLAN.md
- [x] 01.1-03-PLAN.md
- [x] 01.1-04-PLAN.md
- [x] 01.1-05-PLAN.md
- [x] 01.1-06-PLAN.md
- [x] 01.1-07-PLAN.md
- [x] 01.1-08-PLAN.md

- [x] 01.1-01: Theme system — `RedraftTheme(themeMode, dynamicColor, amoled)` in `ui/theme/Theme.kt` (colour logic moved out of `MainActivity`), `ThemeMode` enum, static brand light/dark schemes for pre-Android-12 and dynamic-color-off, optional AMOLED true-black, `Type.kt`, DayNight XML parent + `values-night/themes.xml` (kill the launch flash), system-bar icon colours in every mode
- [x] 01.1-02: Settings storage — `data/SettingsRepository.kt` on its own DataStore file via a single top-level `preferencesDataStore` delegate (same discipline as recents), keys for theme mode / dynamic colour / AMOLED plus only the viewer options that exist, `StateFlow` via `SettingsViewModel`, `MainActivity` collects it so changes apply instantly, Android-free logic unit-tested
- [x] 01.1-03: Settings screen — new `settings` route, gear action on the Home top bar, Appearance (theme mode, dynamic colour on Android 12+, AMOLED), Viewer (only options that work now, e.g. keep screen on, reopen at last page), Storage (clear recents, clear bitmap + thumbnail caches), About and Support entries
- [x] 01.1-04: About + Support + donations — app name, `BuildConfig.VERSION_NAME`, one-line description, honest status line ("Viewer today; true content editing is in development"), source/issue/license links, open-source notices with every dependency licence verified (not copied from memory), privacy statement, `SupportLinks.kt` as the single home for every URL, donations via external links only (`ACTION_VIEW`/Custom Tab) and never feature-gated
- [x] 01.1-05: Home and viewer polish — Home empty state, file size / last-opened date on recents, page indicator ("3 / 12"), reset-zoom and fit-width, tap to hide and show the bars, consistent spacing, ≥48dp touch targets, content descriptions on every icon (skip the reading night mode — it fights rendering and later editing)
- [x] 01.1-06: App identity — final adaptive icon with a monochrome layer for themed icons on Android 13+, splash decision
- [x] 01.1-07: Release prep — settle `applicationId` now (once published, changing it breaks updates), release keystore created and backed up in two places and never committed, passwords from `local.properties`/env vars, R8 keep rules for PDFium/JNI classes, release APK installed and manually tested on a real device, ABI decision (`arm64-v8a` + `x86_64` today — 32-bit-only devices cannot install), semantic `versionName` with an incremented `versionCode`, git tag `v0.1.0`, GitHub Release with APK + SHA-256 checksum, repo hygiene (README screenshots, issue templates), Orion submission per Orion's current rules

### Phase 2: Selection & Hit-Testing

**Goal**: A tap reliably selects the object the user meant — the prerequisite for every edit operation.
**Mode**: mvp
**Depends on**: Phase 01.1 (UI, Settings & Release Prep), which follows Phase 1
**Requirements**: [EDIT_TEXT-01, EDIT_TEXT-02]
**Success Criteria** (what must be TRUE):

  1. User taps text and sees exactly that text object's bounding box drawn as an overlay (correct at any zoom/pan/rotation)
  2. Visually continuous text split into multiple PDFium runs selects as one selectable line (same font/size/color/direction/baseline merged); cross-line block identity is carried as metadata only, never used for selection or the overlay
  3. Tapping empty page space clears the selection; images are out of scope for this phase — the sealed hit result reserves an `Image` variant for a later phase
  4. Selection is stable across re-renders and device rotation

**Carried from Phase 1 (fix at the source — do not skip):** the transform receives
double-rotated, crop-blind geometry. `PdfDocumentHandle.pageSize`
(`pdfcore/src/main/kotlin/com/redraft/pdfcore/PdfDocumentHandle.kt`) already converts the
rotation (`rotationDegrees = getPageRotation() * 90`), but it passes PDFium's **already-rotated**
page width/height while `PdfViewportTransform` swaps width/height **again** for 90/270 — so
quarter-turn pages display portrait today (VIEWER-06) and every inverse mapping hit-testing
relies on is off. The transform also carries no CropBox origin, and an uncoerced `-1` rotation
normalises to 270°. Phase 2 fixes all three at the source: feed the UNROTATED crop size plus a
coerced 0..3 index into the transform, subtract the crop origin exactly once inside it, and add
**no second rotation step** anywhere else (renderer, hit test or overlay).

**Plans**: 3/3 plans executed

Plans:

- [x] 02-01: Hit-testing — tap → PDFium object via inverse transforms; text object enumeration
- [x] 02-02: Logical block merging for fragmented runs (proximity + font metrics) + selection overlay with bbox drawing
- [x] 02-03: Gap closure — tap on lift, handle-keyed page geometry, edit-mode-gated Main-confined extraction lifecycle, production `groupBlocks` wiring, rotation oracle

**Open after 02-03** (see `02-03-SUMMARY.md` §Remaining): overview thumbnail `.dp` sizing (CR-05), drift-session fixes 1–5, `TextExtractionTest.kt`, and on-device UAT.

### Phase 3: Text Editing MVP

**Goal**: The core value — user edits existing text inside the PDF, undoes it, and the page re-renders correctly, even on subset-font documents.
**Mode**: mvp
**Depends on**: Phase 2
**Requirements**: [EDIT_TEXT-03, EDIT_TEXT-04, STATE-01, STATE-02, STATE-03, FALLBACK-01, FALLBACK-02]
**Success Criteria** (what must be TRUE):

  1. User taps text, an inline editor opens pre-filled with original content/font size/color; on commit the page re-renders showing the new text
  2. Undo/redo walks through every edit; the page re-renders to match stack position; state survives rotation
  3. Editing to include characters missing from a subset font falls back to a bundled font on the affected object — no corrupted/blank glyphs
  4. Heavily fragmented text remains editable at run level without corrupting neighboring content

**Plans**: 3

Plans:

- [ ] 03-01-PLAN.md — Tracer: End-to-end "edit one word" — JNI Path A + Editor UI + re-render + reselection
- [ ] 03-02-PLAN.md — JNI Path B + Font Fallback + Command Stack + Object Matching
- [ ] 03-03-PLAN.md — Cross-fade + Error Handling + Rotation Survival + UX Polish + Integration Tests

### Phase 4: Image & Object Editing

**Goal**: User can manipulate images and reposition/delete text blocks — completing the object-editing surface.
**Mode**: mvp
**Depends on**: Phase 3
**Requirements**: [EDIT_TEXT-05, EDIT_IMG-01, EDIT_IMG-02, EDIT_IMG-03, EDIT_IMG-04, EDIT_IMG-05]
**Success Criteria** (what must be TRUE):

  1. User can move a selected image and resize it with corner handles; re-render shows the new placement
  2. User can delete a selected image; user can replace one with a picked image fitted to the original bounds
  3. User can move and delete a text block
  4. Every image/text mutation is undoable via the command stack
  5. User taps an image and sees its bounding box overlay, and tapping empty space clears the selection (deferred from Phase 2, whose locked scope is text-only)

**Plans**: TBD

Plans:

- [ ] 04-01: Image transforms — move/resize via CTM updates, delete, replace (SAF image picker, fit-to-bounds)
- [ ] 04-02: Text block move/delete commands + re-render integration

### Phase 5: Save & Hardening

**Goal**: Edits persist safely — save-as-copy with validated content streams — and the app fails gracefully on hostile inputs.
**Mode**: mvp
**Depends on**: Phase 4
**Requirements**: [IO-01, IO-02, IO-03, IO-04]
**Success Criteria** (what must be TRUE):

  1. User saves via the system create-document picker; a new file is written; the original file is never modified
  2. Saved output re-renders identically to the in-app view (validated by re-render before write)
  3. After save, undo continues to work correctly against the session (stack checkpointed — no divergence)
  4. Corrupt, encrypted, and scanned/image-only PDFs each fail or degrade with a clear user message; save I/O errors are reported without losing the edit session

**Plans**: TBD

Plans:

- [ ] 05-01: Content-stream regeneration + save-as-copy via SAF + pre-write re-render validation
- [ ] 05-02: Undo-stack checkpoint on save + error handling matrix (corrupt/encrypted/scanned/permission revocation) + perf polish

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 01.1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation & Viewer | 14/14 | Complete    | 2026-10-20 |
| 01.1. UI, Settings & Release Prep | 8/8 | Complete    | 2026-10-25 |
| 2. Selection & Hit-Testing | 3/3 | Plans executed — verification gaps remain | - |
| 3. Text Editing MVP | 0/3 | Not started | - |
| 4. Image & Object Editing | 0/2 | Not started | - |
| 5. Save & Hardening | 0/2 | Not started | - |
