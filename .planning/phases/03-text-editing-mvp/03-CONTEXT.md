# Phase 3: Text Editing MVP - Context

**Gathered:** 2026-11-06
**Status:** Ready for planning
**Mode:** discuss (interactive)

<domain>
## Phase Boundary

Phase 3 delivers the **core value** of Redraft: inline text editing with a command stack from day one. A user taps a text line, opens an editor pre-filled with the original content/font/size/color, commits the change, and the page re-renders showing the new text. Undo/redo walks through every edit. Font-subset fallback prevents corrupted glyphs.

**In scope:** Inline editor UI (docked panel above IME), "Edit text" action in viewer top bar (and double-tap shortcut), `EditCommand` stack in ViewModel, PDFium text mutation via custom JNI (FPDFText_SetText or delete+insert), font-subset detection with bundled fallback (Liberation fonts), full page re-render with cache invalidation for changed page only, zoom/pan/selection preservation, undo/redo survival across rotation.

**Out of scope:** Image/object editing (Phase 4), save-as-copy (Phase 5), paragraph reflow (Phase 5), new text block insertion (v2), rich formatting (v2), cross-page copy-paste (v2), OCR (v2), encrypted PDF editing (out of scope), vertical/RTL editing (explicitly disabled in Phase 2 — D-11).
</domain>

<decisions>
## Implementation Decisions

### Inline Editor UI/UX (D-301)

- **D-301-appearance**: A **docked panel above the IME** (uses `imePadding`, clear of navigation bar), not a modal sheet. It does not dim the page; the page stays visible above it.
- **D-301-trigger**: Primary trigger is an **"Edit text" action in the viewer top bar**, next to the Edit-mode pencil. Appears only in Edit mode when a line is selected. For non-editable lines (vertical/RTL), show disabled with snackbar on tap explaining why (e.g., "This text can't be edited: vertical or right-to-left text").
- **D-301-shortcut**: A **second tap on the already-selected line** also opens the editor. Do not use double-tap (reserved for zoom) or long-press.
- **D-301-focus-behavior**: While the editor is open, **taps on the page are ignored** so a draft can't be lost; scroll, pinch, and double-tap zoom still work. The top bar stays visible in Edit mode. When the editor opens it takes focus and shows the keyboard.
- **D-301-layout**: Single-line `TextField`, pre-filled with the line's text **without line-break characters or leading indentation** (indentation is position, not text).
- **D-301-commit**: Enter or Done hides the keyboard; it never inserts a newline. An **Apply button** commits and **Cancel discards**. Apply is disabled while the text is unchanged.
- **D-301-inline-notices**: Before Apply, show inline notices:
  - "Font will change to `<name>`" when a substitute font is needed (FALLBACK-01)
  - "Text is longer than the original line" when it would pass the right margin
- **D-301-no-live-preview**: No live preview in this phase — Apply commits and the page re-renders.
- **D-301-back-handling**: Back closes the editor first (discarding the draft), then clears the selection, then leaves Edit mode, then leaves the viewer.
- **D-301-state-survival**: The draft text, caret, and selection **survive rotation** (kept in the ViewModel).
- **D-301-accessibility**: The field has a content description; Apply and Cancel have labels for TalkBack. The Edit action has content description "Edit selected text".

### Command Stack Architecture (D-302)

- **D-302-granularity**: **Per-commit** (not per-keystroke). One `EditCommand` per Apply. The command stores: `documentId`, `pageIndex`, `lineId`/`blockId` (from selection), `oldText`, `newText`, `oldFontName`, `newFontName`, `fontSizePt`, `color`, `baselineRect` (PDF coords).
- **D-302-stack**: Owned by `PdfViewerViewModel` (lifecycle-bound, survives rotation). Two stacks: `past` (undo) and `future` (redo). Push on Apply; clear `future` on new edit; pop on undo/redo.
- **D-302-execute/undo**: `execute()` applies the mutation via JNI, triggers re-render, updates selection to the new line. `undo()` applies inverse mutation (swap old/new), triggers re-render, updates selection.
- **D-302-survival**: Command stack, current draft, and selection survive rotation via ViewModel. Do not survive process death (acceptable for v1).
- **D-302-checkpoint**: Save (Phase 5) checkpoints the stack — undo after save works against session state only, not the saved file.

### PDFium Text Mutation Strategy (D-303)

- **D-303-binding-gap**: The pinned `pdfiumandroid 3.0.4-alpha01` bridges **only `FPDF_SaveAsCopy`**. It has no page-object enumeration, text-object get/set, font loading, object insert/remove, or content regeneration.
- **D-303-native-apis**: `libpdfium.so` exports: `FPDFPage_CountObjects`, `FPDFPage_GetObject`, `FPDFPageObj_GetBounds`, `FPDFTextObj_GetText`, `FPDFText_SetText`, `FPDFText_LoadStandardFont`, `FPDFText_LoadFont`, `FPDFPageObj_CreateTextObj`, `FPDFPage_InsertObject`, `FPDFPage_RemoveObject`, `FPDFPage_GenerateContent`, `FPDF_SaveAsCopy`.
- **D-303-decision**: **Build a small custom JNI module (CMake)** that links to the `libpdfium.so` shipped in the AAR, using PDFium headers matching that build. Expose only what Phase 3 needs.
- **D-303-ownership**: The JNI module owns an **independent editing document** opened from the file, not from the render document's handles.
- **D-303-workflow**: After each Apply:
  1. Write the edited document to a temp file with `FPDF_SaveAsCopy`
  2. Reopen the rendering document from that copy
  3. Invalidate only the changed page's caches (bitmap at every zoom bucket, thumbnail, text layout)
- **D-303-mutation-path-a** (preferred): Use `FPDFText_SetText` on the **existing text object** when:
  - The line maps to exactly one text object (PDFium text object, not a run)
  - The font can encode every new character
- **D-303-mutation-path-b** (fallback): **Remove the object and insert a new one** with the same matrix, size, and color, using a bundled font, then `FPDFPage_GenerateContent`. Used when:
  - Line spans multiple text objects (merged runs)
  - Font cannot encode new characters
  - Any other case
- **D-303-never**: Never edit at the run level (PDFium text objects are the atomic unit).
- **D-303-ownership-rules**: Document and test:
  - An inserted object belongs to the page
  - An object never inserted must be destroyed
  - A loaded font is closed only after its objects are finished
  - No handle is used after its document is closed
- **D-303-first-plan**: Feasibility spike — change one word in a generated sample PDF, save a copy, open the copy in this app, in Chrome, and in a second viewer, confirm only that word changed. Stop and report if any step fails.

### Font-Subset Fallback (D-304)

- **D-304-keep-original** only if **both**:
  - (a) Every character of the new text already occurs in the line's original text, **OR**
  - (b) The font is **not a subset** (no six-letter `+` prefix) **AND** is one of the standard 14 fonts **AND** the new characters are in WinAnsi
- **D-304-safe-operations**: Deleting or reordering characters is always safe (keeps original font).
- **D-304-replace-whole-line**: In every other case, **replace the whole line (never part of it)** with one new text object in a bundled font.
- **D-304-bundled-fonts**: **Liberation Serif, Sans, and Mono** in Regular, Bold, Italic, Bold Italic. Loaded once per editing document and reused so the saved file does not grow with each edit.
- **D-304-font-selection**: Chosen from the original font name (Times/serif → Liberation Serif; Arial/Helvetica/sans → Liberation Sans; Courier/mono → Liberation Mono; default → Sans) and its bold/italic flags.
- **D-304-preserve**: Keep original size, color, and position.
- **D-304-coverage**: Only Latin characters supported for now. If new text contains anything outside bundled fonts' coverage, **disable Apply** and show which character is unsupported.
- **D-304-notice**: Show "Font will change to `<name>`" before Apply whenever a substitute is used.
- **D-304-licences**: Add SIL Open Font License to the open-source licences screen (About). Note APK size increase in the plan.
- **D-304-test-matrix**: Subset font with missing letter, standard font, bold line, italic line, mono line, unsupported character.

### Re-render After Edit (D-305)

- **D-305-full-page-re-render**: After Apply, render the **entire page** at current zoom through the existing `PageBitmapCache` — simplest, uses existing infrastructure.
- **D-305-cache-invalidation**: Invalidate **only the edited page**: bitmap cache entries at every zoom bucket, its thumbnail, and its cached text layout. **Do not clear other pages' bitmaps.**
- **D-305-preserve-state**: Keep zoom, `panX`, scroll position, and visible page **unchanged**.
- **D-305-selection-restore**: Rebuild the page's text layout, then **reselect the edited line** by matching the new line at the same position, show the selection overlay on it.
- **D-305-cross-fade**: Keep the old bitmap on screen until the new one is ready; swap with a **short cross-fade** so the page never flashes blank.
- **D-305-safe-reopen**: The new document is opened and verified **before** the old one is closed. In-flight renders for the old document are cancelled. Page handles are **never used across documents**.
- **D-305-failure-mode**: If reopen or render fails, keep showing the old document, keep the edit as unsaved state, show a snackbar with the error. Undo/redo use the same path.
- **D-305-test**: Edit page 3 → check pages 2 and 4 keep cached bitmaps, zoom/scroll unchanged, overlay lands on new text, failed reopen leaves previous document intact.

### the agent's Discretion

- Exact JNI module structure, CMake config, and ABI handling (arm64-v8a + x86_64).
- `EditCommand` data class fields and serialization (if needed for future checkpointing).
- How the editing document temp file is managed (naming, cleanup, concurrency).
- Exact text-object matching logic: given a `HitResult` (line + charIndex), find the corresponding PDFium text object in the editing document.
- Bundled Liberation font TTF asset management (res/raw or assets).
- Cross-fade animation duration and easing.
- Whether the editor panel is a separate composable or inline in `PdfViewerScreen`.

### Folded Todos

None — `todo.match-phase 3` returned 0 matches.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Scope & requirements (binding)
- `.planning/ROADMAP.md` § Phase 3 — goal, success criteria, 3 plan bullets (03-01 command stack + editor UI, 03-02 text mutation + re-render, 03-03 font-subset fallback).
- `.planning/REQUIREMENTS.md` — EDIT_TEXT-03, EDIT_TEXT-04, STATE-01, STATE-02, STATE-03, FALLBACK-01, FALLBACK-02.
- `.planning/PROJECT.md` — Core Value, Constraints (Kotlin/Compose + PDFium via NDK/JNI, minSdk 26, arm64-v8a/x86_64, edit PDFium objects directly, no parallel document model).

### Prior phases (locked decisions to stay consistent with)
- `.planning/phases/01-foundation-viewer/01-CONTEXT.md` — D-05 vertical list, D-06 shared zoom/pan in ViewModel, D-07 ViewModel state survives rotation, D-12 single document owner, D-14 transform contract in pdfcore, D-15 intrinsic `/Rotate`.
- `.planning/phases/01.1-ui-settings-release-prep/01.1-CONTEXT.md` — Theme tokens, Settings/DataStore pattern, MVVM+UDF, `RedraftTheme`, brand colours.
- `.planning/phases/02-selection-hit-testing/02-CONTEXT.md` — D-01 text-only read-only, D-02 coordinate transform reuse, D-06 line-level selection, D-07 blockId metadata, D-10 oriented boxes, D-11 `editable=false` for vertical/RTL, D-18 Edit mode toggle, D-19 tap behavior in Edit mode, D-23 selection state shape, D-24 overlay on selected page only, D-26 amber outline/fill, D-28 extraction lifecycle, D-30/31 messages.

### Coordinates, viewer, and selection integration
- `pdfcore/src/main/kotlin/com/redraft/pdfcore/PdfViewportTransform.kt` — screen↔PDF transform (reused verbatim).
- `pdfcore/src/main/kotlin/com/redraft/pdfcore/selection/HitTest.kt` — `HitResult` with `charIndex`, `caretStartPdf`, `caretEndPdf`.
- `pdfcore/src/main/kotlin/com/redraft/pdfcore/selection/BlockGrouping.kt` — `groupBlocks`, deterministic `blockId`.
- `app/src/main/kotlin/com/redraft/ui/PdfViewerViewModel.kt` — `selectionState`, `editMode`, `onTap`, extraction lifecycle, `PageTextState`, `ViewerMessage`.
- `app/src/main/kotlin/com/redraft/ui/viewer/SelectionOverlay.kt` — overlay drawing, oriented path, theme tokens.
- `app/src/main/kotlin/com/redraft/ui/viewer/ViewerRoute.kt` — Edit mode pencil, top bar actions, back handler, snackbar host.
- `app/src/main/kotlin/com/redraft/ui/viewer/PageBitmapCache.kt` — bitmap cache with recycling, `recycleAll()`, per-bucket keys.

### Binding and native
- `io.legere:pdfiumandroid:3.0.4-alpha01` AAR — contains `libpdfium.so` (all ABIs) and the Kotlin suspend API. **Does not expose editing APIs.**
- PDFium headers matching the AAR's Chromium build — needed for JNI module.
- `tools/gen_sample_pdf.py` — deterministic test PDF generator; extend for Phase 3 fixtures.

### External
- Liberation fonts (SIL OFL): https://github.com/liberationfonts/liberation-fonts
- PDFium editing API: `FPDFText_SetText`, `FPDFPageObj_CreateTextObj`, `FPDFPage_InsertObject`, `FPDFPage_RemoveObject`, `FPDFPage_GenerateContent`, `FPDF_SaveAsCopy`
- Android JNI / CMake: NDK r28+, CMake 3.22+

No SPEC.md exists for this phase. Requirements from REQUIREMENTS.md and ROADMAP success criteria.
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **PdfViewportTransform**: Pure, Android-free. `screenToPdf` / `pdfToScreen` / `pdfUnitsPerPixel` / `renderedSize`. Single source of truth for coordinates.
- **HitTest.kt**: `HitResult` carries `line`, `charIndex`, `caretStartPdf`, `caretEndPdf`. `charIndex` = first char index in page's flat char list — can be used to locate the text object in the editing document.
- **BlockGrouping.kt**: `groupBlocks` produces deterministic `blockId` (`p{pageIndex}:b{firstCharIndex}`). Selection overlay already carries `blockId`.
- **PdfViewerViewModel**: Owns `selectionState`, `editMode`, extraction lifecycle, `PageBitmapCache`, `ThumbnailCache`. Command stack, draft text, and editor state fit here.
- **PageBitmapCache**: `sizeBytes`, `recycleAll()`, per-zoom-bucket entries. Invalidation = remove entries for a page index.
- **ViewerRoute**: Edit mode pencil, top bar actions, back handler, `SnackbarHost`. Add "Edit text" action next to pencil.
- **SelectionOverlay**: Draws oriented rectangle via transform. Reuse for reselection after re-render.
- **Theme tokens**: `MaterialTheme.colorScheme.primary` (amber), `outline` (grey). No hardcoded hex.

### Established Patterns
- **MVVM + UDF**: `ViewModel` owns state as `StateFlow`; composables are stateless.
- **Single-owner document handle** with short-lived page handles (`use { }`). JNI module will have its own editing document.
- **All PDFium work on `Dispatchers.IO`**; never on main thread.
- **One-shot scroll commands** vs. mirrored scroll state; selection state must not move the list.
- **Coil-free, explicit bitmap lifecycle**; selection state never retains bitmaps or text-page objects.
- **Compose overlay + `graphicsLayer` transform**, pan as layout offset; overlay uses same transform.
- **String resources** for all user-facing text (`strings.xml`).
- **JVM unit tests** in `app/src/test/kotlin` for pure logic (hit test, line reducer, block grouping, command stack).

### Integration Points
- `PdfViewerViewModel`:
  - Add `editorState` (draft text, caret, showing, target line ref)
  - Add `commandStack` (past/future `EditCommand`)
  - Add `onEditText()` → opens editor panel with selection's line text
  - Add `onApplyEdit(newText)` → creates `EditCommand`, executes, pushes to stack
  - Add `onUndo()` / `onRedo()` → pops command, executes inverse
- `ViewerRoute`:
  - Add "Edit text" `IconButton` in actions (enabled only in Edit mode + selection + editable)
  - Show disabled with snackbar for non-editable lines
  - Editor panel composable (docked, imePadding, no dim)
- `PdfViewerScreen`:
  - Host editor panel (visible when `editorState.showing`)
  - Double-tap on selected line → `viewModel.onEditText()`
  - Ignore page taps while editor showing (except gestures)
- `pdfcore` JNI module (new):
  - `editDocument.open(uri)` / `close()`
  - `setText(pageIndex, objectIndex, newText)` → `FPDFText_SetText`
  - `replaceTextObject(pageIndex, objectIndex, newText, font, size, color, matrix)` → remove+insert+generate
  - `saveAsCopy(tempPath)` → `FPDF_SaveAsCopy`
  - `loadBundledFont(fontName, bold, italic)` → `FPDFText_LoadFont` from asset
- `PageBitmapCache`: `invalidatePage(pageIndex)` removes all buckets for that page.

</code_context>

<specifics>
## Specific Ideas

### Editor Panel Copy (from discussion)
- Title: "Edit Text"
- Field hint: "Type new text"
- Inline notice (amber): "Font will change to Liberation Sans"
- Inline notice (amber): "Text is longer than the original line"
- Buttons: Cancel (outlined) / Apply (filled, primary, disabled when unchanged)
- Content description: "Editing line: {first 30 chars of original text}"

### Bundled Font Assets
- Liberation Serif: Regular, Bold, Italic, Bold Italic (4 TTFs)
- Liberation Sans: Regular, Bold, Italic, Bold Italic (4 TTFs)
- Liberation Mono: Regular, Bold, Italic, Bold Italic (4 TTFs)
- Total: 12 TTF files in `app/src/main/assets/fonts/liberation/`
- Load via `AssetManager.openFd` → `FPDFText_LoadFont` from file descriptor

### JNI Module API (Kotlin interface)
```kotlin
interface PdfEditingEngine {
    suspend fun open(uri: String): Result<EditingHandle>
    suspend fun close(handle: EditingHandle)
    suspend fun setText(handle: EditingHandle, pageIndex: Int, objectIndex: Int, newText: String): Boolean // true = in-place worked
    suspend fun replaceTextObject(
        handle: EditingHandle,
        pageIndex: Int,
        objectIndex: Int,
        newText: String,
        fontName: String,
        fontSizePt: Float,
        color: Int,
        matrix: FloatArray // CTM
    )
    suspend fun saveAsCopy(handle: EditingHandle, destPath: String): Boolean
    suspend fun loadBundledFont(handle: EditingHandle, fontName: String, bold: Boolean, italic: Boolean): FontHandle
}
```

### Text Object Matching
Given `HitResult.charIndex` (first char index in page's flat char list from `TextPageLayout`), find the PDFium text object in the editing document that contains that character. Since `TextPageLayout.chars` comes from `FPDFText_GetCharBox` in order, and PDFium text objects are in reading order, we can map by accumulating character counts per text object until we reach `charIndex`.

### Test Fixtures (extend gen_sample_pdf.py)
- Subset font missing 'z' — edit to add 'z'
- Standard 14 font (Times-Roman) — edit within WinAnsi
- Bold line, Italic line, Bold Italic line
- Courier (mono) line
- Line with emoji (unsupported) → Apply disabled
- Multi-object line (merged runs) → triggers replace path

</specifics>

<deferred>
## Deferred Ideas

- **Image selection/editing** — Phase 4
- **Save-as-copy** — Phase 5
- **Paragraph reflow** — Phase 5 (EDIT_TEXT-02 reworded)
- **New text block insertion** — v2 (EDIT_TEXT-10)
- **Rich formatting (bold/italic/alignment)** — v2 (EDIT_TEXT-11)
- **Text search** — v2 (EDIT_TEXT-12)
- **Cross-page copy-paste** — v2
- **OCR for scanned pages** — v2 (OCR-01)
- **Encrypted PDF password entry** — out of scope
- **Vertical/RTL editing** — explicitly disabled (Phase 2 D-11, `editable=false`)

### Reviewed Todos (not folded)
None — no pending todos matched this phase.
</deferred>

---

*Phase: 03 — Text Editing MVP*
*Context gathered: 2026-11-06 (discuss mode)*