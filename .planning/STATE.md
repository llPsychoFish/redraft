---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
current_phase: 02
status: completed
stopped_at: Phase 02 execution resumed after interrupted session — Plan 02-02 partial (Task 6 generator + fixtures committed c41ea54; Tasks 1-5 and test files outstanding; SUMMARY.md missing). Executing wave 2.
last_updated: "2026-10-28T00:32:28.978Z"
last_activity: 2026-10-28
last_activity_desc: Phase 02 marked complete
progress:
  total_phases: 3
  completed_phases: 3
  total_plans: 24
  completed_plans: 24
current_phase_name: Selection & Hit-Testing
state_head: c41ea54
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-16)

**Core value:** Editing existing PDF content must actually work — true content editing inside PDFs, not annotation.
**Current focus:** Phase 02 — Selection & Hit-Testing

## Current Position

Phase: 02 — COMPLETE
Plan: 1 of 2
Status: Phase 02 complete
Last activity: 2026-10-28 — Phase 02 marked complete

Progress: [██████████] 95% - Phase 1 complete (6 phases in the milestone, 1 done)

## Performance Metrics

**Velocity:**

- Total plans completed: 22
- Average duration: —
- Total execution time: — hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 14 | - | - |
| 01.1 | 8 | - | - |

**Recent Trend:**

- Last 5 plans: —
- Trend: —

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 1 P01-01 | 0 | 3 tasks | 22 files |
| Phase 1 P01-02 | 0 | 3 tasks | 13 files |
| Phase 1 P01-03 | 0 | 3 tasks | 12 files |
| Phase quick-01 P01 | 3min | 3 tasks | 2 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- 2026-10-17: Project mode = Vertical MVP (mvp); 5 phases derived from research SUMMARY.md ordering (viewer → selection → text → image → save)
- 2026-10-17: FALLBACK category added (subset fonts, fragmented runs) — mapped to Phase 3 with degraded-mode requirement FALLBACK-02
- 2026-10-17: Use the pdfiumandroid suspend API (PdfiumCoreKt/PdfDocumentKt/PdfPageKt) so threading confinement is library-enforced rather than reimplemented in pdfcore
- 2026-10-17: Tracer test document (app/src/main/assets/sample.pdf) is generated deterministically by tools/gen_sample_pdf.py, not a committed opaque binary
- 2026-10-17: ViewModels stay Android-framework-free (plain ViewModel + RecentsStore interface + String URIs) so they are JVM-testable without Robolectric
- 2026-10-17: Navigation uses a replay=0 SharedFlow event rather than state-derived navigation, so returning to Home does not re-navigate to the viewer
- 2026-10-17: PdfViewportTransform is pure/`android.graphics`-free in pdfcore so Phase 2 hit-testing reuses it verbatim; page layout is driven by `renderedSize` (rotation-aware)
- 2026-10-17: Zoom clamped to [1f, 5f] and quantized to Z_00_SCALE_BUCKETS; pinch live-scales the cached bitmap and re-renders only after a 250 ms settle

### Pending Todos

None yet.

### Blockers/Concerns

- pdfiumandroid 3.0.4-alpha01 is alpha — MEDIUM stability risk; pin exact version (research flag)
- Content-stream regeneration strategy (minimal diff vs full) unresolved — Phase 3/5 deep research needed
- 01-01 on-device verification outstanding (no emulator this session): render page 1 of sample.pdf, rotate, no main-thread PDFium call, PdfHandleRegistry.openCount == 0 after disposal
- 01-02 on-device verification outstanding: SAF pick → viewer, recents survive relaunch, corrupt/encrypted/revoked degrade without crashing
- 01-03 on-device verification outstanding: 100+ page scroll/memory bound, pinch-zoom/pan survive rotation, double-tap fit-width ⇄ 2×, no StrictMode PDFium-on-main violations, intrinsic /Rotate layout

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 261022-fub | Redesign About and Support screens with grouped M3 cards (Fossify/PixelPlayer patterns); Support platforms = GitHub Sponsors / Buy Me a Coffee / PayPal / Crypto; filled GitHub URLs | 2026-10-22 | 2e18bb8 | [261022-fub-redesign-about-and-support-donate-screen](./quick/261022-fub-redesign-about-and-support-donate-screen/) |
| 261022-hyn | Fix Open PDF button contrast in all themes (opaque primary/onPrimary at button level only) and keep Settings switch toggles inside viewport (weight(1f) labels); Color.kt/Theme.kt untouched | 2026-10-22 | 003a334, e1d7490 | [261022-hyn-fix-open-pdf-fab-contrast-theme-bug-and-](./quick/261022-hyn-fix-open-pdf-fab-contrast-theme-bug-and-/) |
| 261022-jcc | Fix predictive back gesture flicker on About/Support/Settings (explicit M3 slide+fade NavHost transitions replacing default cross-fade) | 2026-10-22 | 3b81327 | [261022-jcc-fix-predictive-back-gesture-flicker-glit](./quick/261022-jcc-fix-predictive-back-gesture-flicker-glit/) |
| 261022-mcb | Bundle genuine Inter font TTF assets into res/font and activate in RedraftTypography | 2026-10-22 | ce7c92a | [261022-mcb-bundle-inter-font-ttf-and-use-in-typography](./quick/261022-mcb-bundle-inter-font-ttf-and-use-in-typography/) |
| 261022-k5s | Fix predictive back gesture flicker and window leaks via background Surface wrap and 25% pure-slide pop transitions; wrap About chips on FlowRow | 2026-10-22 | a2b5541 | [261022-k5s-fix-predictive-back-window-leaks-on-abou](./quick/261022-k5s-fix-predictive-back-window-leaks-on-abou/) |
| 261023-hb1 | Fix PDF viewer top bar overlap: page content renders behind the TopAppBar and into the status bar area in ViewerRoute | 2026-10-23 | 2e5a499 | [261023-hb1-fix-pdf-viewer-top-bar-overlap-page-cont](./quick/261023-hb1-fix-pdf-viewer-top-bar-overlap-page-cont/) |
| 261023-jlt | Add PDF intent-filter and uses-permission to AndroidManifest.xml, plus runtime storage permission request in HomeScreen.kt Compose UI | 2026-10-23 | f5c493a | [261023-jlt-fix-open-with-and-storage-permissions-fo](./quick/261023-jlt-fix-open-with-and-storage-permissions-fo/) |
| 261023-jlt | Fix Open With and storage permissions for Redraft PDF viewer | 2026-10-23 | f5c493a | [261023-jlt-fix-open-with-and-storage-permissions-fo](./quick/261023-jlt-fix-open-with-and-storage-permissions-fo/) |
| 261023-lr6 | Fix Open With Redraft never opening the PDF (intent handling, buffered event, NavHost collector) and the top bar covering the start of page 1 | 2026-10-23 | 72d9857 | [261023-lr6-apply-patch-fixes-for-1-open-with-redraf](./quick/261023-lr6-apply-patch-fixes-for-1-open-with-redraf/) |
| 261023-mt0 | Create PdfViewerViewModel in MainActivity via by viewModels so incoming PDF intents do not crash | 2026-10-23 | ed78af2 | [261023-mt0-apply-updated-redraft-fixes-patch-create](./quick/261023-mt0-apply-updated-redraft-fixes-patch-create/) |
| 11 | Add device screenshots (docs/screenshots) and wire them into README Screenshots section | 2026-10-23 | 2a4d708 | — |

### Roadmap Evolution

- Phase 01.1 inserted after Phase 1: UI, Settings & Release Prep (URGENT)

## Deferred Items

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| VIEWER-06 (Phase 1 wiring) | The page's `/Rotate` never reaches `PdfViewportTransform`: `PdfDocumentHandle.pageSize` stores PDFium's `getPageRotation()` — an **index 0..3**, not degrees — in a field named `rotationDegrees`, so `normalizeRotation` collapses it to 0 and the 90/270 arms of the transform are unreachable in production (`pdfcore/src/main/kotlin/com/redraft/pdfcore/PdfDocumentHandle.kt:50`). No Phase-1 user-visible impact (PDFium's width/height are already rotated, so pages display correctly); it lands on Phase 2's hit-testing. **Fix it at the source** and do NOT swap the dimensions twice — a naive "1 -> 90" would double-swap. | Deferred to Phase 2 — recorded in ROADMAP.md's Phase 2 section ("Carried from Phase 1") | 2026-10-20 (phase 01 re-verification) |

## Session Continuity

Last session: 2026-10-26T00:01:04.419Z
Stopped at: Phase 2 context gathered
Resume file: .planning/phases/02-selection-hit-testing/02-CONTEXT.md
