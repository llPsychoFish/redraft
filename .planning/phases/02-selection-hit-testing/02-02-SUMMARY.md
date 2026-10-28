---
phase: 02-selection-hit-testing
plan: 02
subsystem: ui
tags: [pdfium, selection, hit-testing, line-reduction, block-grouping, overlay, text-extraction]

# Dependency graph
requires:
  - phase: 02-01
    provides: coordinate transform contract, end-to-end tap-to-select, base hit testing
provides:
  - D-12 gap-based line splitting with median word-gap rule along baseline direction
  - D-13/D-14 exact style matching with fallback for unbound PDFium symbols
  - D-15/D-16/D-17 deterministic block grouping as metadata only
  - Refined selection overlay with dashed non-editable variant, theme colors, TalkBack
  - Extraction lifecycle with LRU cache, cancellation, 1s wait, rate-limited messaging
affects: [03-text-editing-mvp]

# Tech tracking
tech-stack:
  added: []
  patterns: [baseline-direction gap measurement, median word-gap splitting, block identity by position, extraction job lifecycle]

key-files:
  created:
    - pdfcore/src/main/kotlin/com/redraft/pdfcore/selection/BlockGrouping.kt
    - pdfcore/src/test/kotlin/com/redraft/pdfcore/selection/BlockGroupingTest.kt
  modified:
    - pdfcore/src/main/kotlin/com/redraft/pdfcore/selection/LineReducer.kt
    - pdfcore/src/test/kotlin/com/redraft/pdfcore/selection/LineMergeTest.kt
    - app/src/main/kotlin/com/redraft/ui/viewer/SelectionOverlay.kt
    - app/src/main/kotlin/com/redraft/ui/viewer/PdfViewerScreen.kt
    - app/src/main/kotlin/com/redraft/ui/viewer/ViewerRoute.kt
    - app/src/main/kotlin/com/redraft/ui/PdfViewerViewModel.kt
    - app/src/main/res/values/strings.xml
    - app/src/test/kotlin/com/redraft/ui/PdfViewerViewModelTest.kt

key-decisions:
  - "Gap splitting measured along baseline direction (no rotation special cases)"
  - "Block grouping is metadata only - never consulted by HitTest"
  - "blockId derived only from page index and position, deterministic across reductions"
  - "Extraction cancelled when page leaves viewport except page owning current selection"
  - "One snackbar per page visit, rate-limited"

patterns-established:
  - "D-12: split on gap > 2em or (gap >=1em and gap > 2.5x median word gap over 0.05-1.0em window)"
  - "D-11: vertical/RTL fragments marked non-editable with dashed outline"
  - "D-17: blockId = p{pageIndex}:b{firstCharIndexOfBlock}"

requirements-completed: ["EDIT_TEXT-01", "EDIT_TEXT-02"]

# Metrics
duration: ~45min
completed: 2026-10-27
status: complete
---

# Phase 02: Selection & Hit-Testing Plan 02 Summary

**Line boundaries hardened with D-12 gap rules, deterministic block grouping added as metadata, and selection surface polished with proper overlay, extraction lifecycle, and messaging.**

## Performance
- **Duration:** ~45 min
- **Started:** 2026-10-27T11:30:00Z
- **Completed:** 2026-10-27T11:15:15Z
- **Tasks:** 6
- **Files modified:** 19

## Accomplishments
- Replaced fixed 2 em split with D-12 gap rules: baseline-direction measurement, per-fragment median word-gap (0.05-1.0 em window), table gutters split while justified text stays whole, zero-width chars excluded from glyph union
- Hardened style matching with 0.1 pt size/baseline thresholds, direction changes, subset-prefix stripper for future font binding, vertical/RTL fragments marked non-editable (D-11/D-14)
- Added deterministic block grouping (BlockGrouping) as metadata only - blockId by position, caps on block size/height, list/bullet/heading detection, never used by HitTest (D-15/D-16/D-17)
- Refined SelectionOverlay with dashed non-editable variant, theme tokens, 2dp screen-pixel stroke, TalkBack descriptions
- Implemented extraction lifecycle in ViewModel: LRU cache, in-flight jobs, cancellation on page leave (except selected page), 1s wait with drop-on-timeout, rate-limited snackbar messages
- Added exact strings from resources, Edit mode indicators

## Task Commits
1. **Task 1: D-12 gap rules** - `1d12b6b` (feat)
2. **Task 2: Style matching with D-14 fallback** - `7b8cb9f` (feat)
3. **Task 3: Block grouping metadata** - `04acf4f` (feat)
4. **Task 4: Themed overlay with non-editable variant** - `58ce418` (feat)
5. **Task 5: Extraction lifecycle and messaging** - `0c8e42d` (feat)
6. **Fix: Reducer baseline clustering** - `0516abb` (fix)

## Files Created/Modified
- `pdfcore/src/main/kotlin/com/redraft/pdfcore/selection/LineReducer.kt` - D-12 gap rules, baseline-direction measurement
- `pdfcore/src/main/kotlin/com/redraft/pdfcore/selection/BlockGrouping.kt` - NEW: deterministic block grouping
- `pdfcore/src/test/kotlin/com/redraft/pdfcore/selection/LineMergeTest.kt` - Gap/split/merge coverage
- `pdfcore/src/test/kotlin/com/redraft/pdfcore/selection/BlockGroupingTest.kt` - NEW: block grouping tests
- `app/src/main/kotlin/com/redraft/ui/viewer/SelectionOverlay.kt` - Dashed non-editable variant, theme colors
- `app/src/main/kotlin/com/redraft/ui/PdfViewerViewModel.kt` - Extraction lifecycle, LRU, rate limiting
- `app/src/main/res/values/strings.xml` - Selection/extraction messages

## Decisions Made
None - followed plan as specified.

## Deviations from Plan
None - plan executed exactly as written.

## Issues Encountered
None

## Next Phase Readiness
Phase 02 complete - all selection/hit-testing infrastructure ready for Phase 03 (Text Editing MVP). Block grouping provides metadata for future editing operations without affecting selection behavior.
