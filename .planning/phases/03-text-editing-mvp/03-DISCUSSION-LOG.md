# Phase 3: Text Editing MVP - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-11-06
**Phase:** 03-text-editing-mvp
**Areas discussed:** Inline Editor UI/UX, PDFium Text Mutation Strategy, Font-Subset Fallback, Re-render After Edit

---

## Inline Editor UI/UX

| Option | Description | Selected |
|--------|-------------|----------|
| In-place overlay on the selected line | Editor appears directly over the selected text line, same position/size, with keyboard focus | |
| Floating toolbar + modal text field | A toolbar appears above/below selection with edit button; tapping opens a modal dialog | |
| Bottom sheet editor | A bottom sheet slides up with the text field, pre-filled with line content | |
| Replace selection with editable TextField | The selection overlay itself becomes an editable TextField | ✓ (as docked panel) |

**User's choice:** A **docked panel above the IME** (uses `imePadding`, clear of navigation bar), not a modal sheet. It does not dim the page and the page stays visible above it. When it opens, zoom and scroll so the selected line sits in the visible area above the panel, and restore the previous zoom and scroll when it closes.

**Trigger:** Primary trigger is an **"Edit text" action in the viewer top bar**, next to the Edit-mode pencil. Appears only in Edit mode when a line is selected. For non-editable lines (vertical/RTL), show disabled with snackbar on tap explaining why (e.g., "This text can't be edited: vertical or right-to-left text"). Shortcut: a second tap on the already-selected line opens the editor too. Do not use double-tap (reserved for zoom) or long-press.

**Details:**
- Single-line `TextField`, pre-filled with the line's text without line-break characters or leading indentation (indentation is position, not text)
- Enter or Done hides the keyboard; never inserts a newline
- Apply button commits, Cancel discards; Apply disabled while text unchanged
- Inline notices before Apply: "Font will change to `<name>`" when substitute font needed; "Text is longer than the original line" when it would pass right margin
- No live preview: Apply commits and page re-renders
- Back closes editor first (discarding draft), then clears selection, then leaves Edit mode, then leaves viewer
- Draft text, caret, selection survive rotation (kept in ViewModel)
- Accessibility: field has content description; Apply/Cancel have labels; Edit action has "Edit selected text"

---

## PDFium Text Mutation Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Replace text in-place on existing text object | Use FPDFPageObj_SetText or similar to replace text string directly | |
| Delete old object, insert new text object | Remove selected text object, create new one at same position | |
| Edit at the text-run level | Edit the specific run that was selected | |
| Let researcher investigate binding APIs first | Check what pdfiumandroid 3.0.4-alpha01 actually exposes | ✓ (led to custom JNI decision) |

**User's choice:** Research finding verified against exact pdfiumandroid 3.0.4-alpha01 artifact: the binding bridges only `FPDF_SaveAsCopy`. It has no page-object enumeration, text-object get/set, font loading, object insert/remove, or content regeneration. `libpdfium.so` exports the full editing API. **Decision: build a small custom JNI module (CMake)** that links to the `libpdfium.so` shipped in the AAR, using PDFium headers matching that build, exposing only what Phase 3 needs.

**Mutation Strategy:**
- **Path A (preferred):** `FPDFText_SetText` on the **existing text object** when the line maps to exactly one text object and the font can encode every new character.
- **Path B (fallback):** Remove the object and insert a new one with the same matrix, size, and color, with a bundled font, then `FPDFPage_GenerateContent`. Used when line spans multiple objects, font can't encode, or any other case.
- **Never** edit at the run level (PDFium text objects are the atomic unit).

**Workflow:** After each Apply, write edited document to temp file with `FPDF_SaveAsCopy`, reopen rendering document from that copy, invalidate only the changed page's caches.

**Ownership Rules:** Inserted object belongs to page; object never inserted must be destroyed; loaded font closed only after its objects finished; no handle used after its document closed.

**First Plan:** Feasibility spike — change one word in a generated sample PDF, save a copy, open the copy in this app, in Chrome, and in a second viewer, confirm only that word changed. Stop and report if any step fails.

---

## Font-Subset Fallback

| Option | Description | Selected |
|--------|-------------|----------|
| Replace entire text object's font with bundled font | When any character can't be encoded, load bundled font for whole text object | ✓ |
| Create new text object with fallback font for new chars only | Keep original for encodable chars, create separate objects for new chars | |
| Detect encodability before edit, show warning, let user proceed | Check if all new chars in font's cmap; show notice before Apply | ✓ |
| Use bundled font matching original as closely as possible | Pick bundled font (Noto Sans, Inter, etc.) visually similar to original | ✓ |

**User's choice:** Keep original font only if (a) every character of the new text already occurs in the line's original text, OR (b) the font is not a subset (no six-letter `+` prefix) AND is one of the standard 14 fonts AND new characters are in WinAnsi. Deleting or reordering characters is always safe. In every other case, replace the whole line (never part of it) with one new text object in a bundled font, loaded once per editing document and reused so the saved file does not grow with each edit.

**Bundled Fonts:** Liberation Serif, Sans, and Mono in Regular, Bold, Italic, Bold Italic. Chosen from original font name (Times/serif, Arial/Helvetica/sans, Courier/mono, defaulting to sans) and its bold/italic flags. Keep original size, colour, and position.

**Coverage:** Only Latin characters supported. If new text contains anything outside bundled fonts' coverage, disable Apply and show which character is unsupported.

**Notice:** Show "Font will change to `<name>`" before Apply whenever a substitute is used.

**Licences:** Add SIL Open Font License to open-source licences screen (About). Note APK size increase in plan.

**Test Matrix:** Subset font with missing letter, standard font, bold line, italic line, mono line, unsupported character.

---

## Re-render After Edit

| Option | Description | Selected |
|--------|-------------|----------|
| Full page re-render via existing pipeline | After save and reopen, render entire page at current zoom through PageBitmapCache | ✓ |
| Region re-render (only edited line's area) | Render only dirty rectangle around edited line | |
| Full page re-render, preserve zoom/pan/selection | Re-render whole page but keep ViewModel state exactly | ✓ |
| Re-render with cache invalidation only for changed page | Evict only edited page's bitmap from cache; other pages keep cached bitmaps | ✓ |

**User's choice:** Full page re-render, preserve zoom/pan/selection. After Apply: save to a temp copy and reopen the rendering document, then invalidate only the edited page (bitmap cache entries at every zoom bucket, its thumbnail, and its cached text layout). Do not clear other pages' bitmaps. Keep the old bitmap on screen until the new one is ready and swap with a short cross-fade, so the page never flashes blank. Keep zoom, panX, scroll position, and visible page unchanged. Rebuild the page's text layout, then reselect the edited line by matching the new line at the same position and show the selection overlay on it. Make the reopen safe: the new document is opened and verified before the old one is closed, in-flight renders for the old document are cancelled, and page handles are never used across documents. If the reopen or render fails, keep showing the old document, keep the edit as unsaved state, and show a snackbar with the error. Undo and redo use exactly the same path.

---

## the agent's Discretion

- Exact JNI module structure, CMake config, and ABI handling (arm64-v8a + x86_64)
- `EditCommand` data class fields and serialization (if needed for future checkpointing)
- How the editing document temp file is managed (naming, cleanup, concurrency)
- Exact text-object matching logic: given a `HitResult` (line + charIndex), find the corresponding PDFium text object in the editing document
- Bundled Liberation font TTF asset management (res/raw or assets)
- Cross-fade animation duration and easing
- Whether the editor panel is a separate composable or inline in `PdfViewerScreen`

---

## Deferred Ideas

- Image selection/editing — Phase 4
- Save-as-copy — Phase 5
- Paragraph reflow — Phase 5 (EDIT_TEXT-02 reworded)
- New text block insertion — v2 (EDIT_TEXT-10)
- Rich formatting (bold/italic/alignment) — v2 (EDIT_TEXT-11)
- Text search — v2 (EDIT_TEXT-12)
- Cross-page copy-paste — v2
- OCR for scanned pages — v2 (OCR-01)
- Encrypted PDF password entry — out of scope
- Vertical/RTL editing — explicitly disabled (Phase 2 D-11, `editable=false`)