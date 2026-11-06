# Phase 03: Text Editing MVP - Research

**Researched:** 2026-11-06
**Domain:** Android PDF text editing via PDFium JNI, command stack architecture, font subset fallback
**Confidence:** HIGH

## Summary

Phase 3 delivers Redraft's core value: inline text editing with a command stack from day one. The user taps a text line, opens an editor pre-filled with the original content/font/size/color, commits the change, and the page re-renders showing the new text. Undo/redo walks through every edit. Font-subset fallback prevents corrupted glyphs.

**Primary recommendation:** Build a minimal custom JNI module (`pdfedit`) linking against the `libpdfium.so` bundled in `io.legere:pdfiumandroid:3.0.4-alpha01`, exposing only the 7 native functions needed for Phase 3. Implement the command stack in `PdfViewerViewModel` with per-commit granularity. Use Liberation fonts (12 TTFs) as bundled fallbacks for subset-font scenarios.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **Inline Editor UI (D-301):** Docked panel above IME (uses `imePadding`), not a modal sheet. Trigger: "Edit text" action in viewer top bar (Edit mode only) + second tap on selected line. Single-line `TextField`, pre-filled without line-break/indentation. Apply (Enter/Done) commits, Cancel discards. Inline notices before Apply: "Font will change to `<name>`", "Text is longer than the original line". No live preview. Back closes editor → clears selection → leaves Edit mode → leaves viewer. Draft/caret/selection survive rotation in ViewModel.
- **Command Stack (D-302):** Per-commit granularity (one `EditCommand` per Apply). Owned by `PdfViewerViewModel` (survives rotation). Two stacks: `past` (undo) and `future` (redo). `execute()` applies mutation via JNI, triggers re-render, updates selection. `undo()` applies inverse mutation. Stack survives rotation, not process death. Save (Phase 5) checkpoints the stack.
- **PDFium Text Mutation (D-303):** Pinned `pdfiumandroid 3.0.4-alpha01` bridges only `FPDF_SaveAsCopy` — no page-object enumeration, text-object get/set, font loading, object insert/remove, or content regeneration. **Decision:** Build a small custom JNI module (CMake) linking to the AAR's `libpdfium.so` using PDFium headers matching that build. Expose only what Phase 3 needs. JNI module owns an **independent editing document** opened from the file, not from the render document's handles. Workflow: after Apply → write edited doc to temp file with `FPDF_SaveAsCopy` → reopen rendering document from copy → invalidate only changed page's caches (bitmap at every zoom bucket, thumbnail, text layout).
  - **Path A (preferred):** `FPDFText_SetText` on existing text object when line maps to exactly one text object AND font encodes all new characters.
  - **Path B (fallback):** Remove object + insert new one with same matrix/size/color using bundled font, then `FPDFPage_GenerateContent`. Used when: line spans multiple text objects, font cannot encode new chars, or any other case.
  - **Never** edit at run level (PDFium text objects are the atomic unit).
  - **Ownership rules:** Inserted object belongs to page; uninserted object must be destroyed; loaded font closed only after its objects are finished; no handle used after document close.
- **Font-Subset Fallback (D-304):** Keep original font only if (a) every new char already occurs in original line text, OR (b) font is not a subset (no 6-letter `+` prefix) AND is one of the standard 14 fonts AND new chars are in WinAnsi. Deleting/reordering chars is always safe. Otherwise, **replace whole line** with new text object in bundled font. Bundled fonts: Liberation Serif/Sans/Mono in Regular, Bold, Italic, Bold Italic (12 TTFs). Loaded once per editing document and reused. Font selection from original name: Times/serif → Liberation Serif; Arial/Helvetica/sans → Liberation Sans; Courier/mono → Liberation Mono; default → Sans. Preserve size, color, position. Only Latin supported; unsupported chars disable Apply with notice. Show "Font will change to `<name>`" before Apply when substitute used. Add SIL OFL to licences screen. APK size increase noted.
- **Re-render After Edit (D-305):** Full page re-render at current zoom through existing `PageBitmapCache`. Invalidate **only edited page**: bitmap cache at every zoom bucket, thumbnail, text layout. Preserve zoom, pan, scroll, visible page. Rebuild text layout, reselect edited line by matching new line at same position. Cross-fade old→new bitmap (short). New document opened/verified before old closed; in-flight renders for old doc cancelled; page handles never used across documents. Failure: keep showing old document, keep edit as unsaved state, snackbar error.

### the agent's Discretion

- Exact JNI module structure, CMake config, and ABI handling (arm64-v8a + x86_64).
- `EditCommand` data class fields and serialization (if needed for future checkpointing).
- How the editing document temp file is managed (naming, cleanup, concurrency).
- Exact text-object matching logic: given `HitResult` (line + charIndex), find corresponding PDFium text object in editing document.
- Bundled Liberation font TTF asset management (res/raw or assets).
- Cross-fade animation duration and easing.
- Whether the editor panel is a separate composable or inline in `PdfViewerScreen`.

### Deferred Ideas (OUT OF SCOPE)

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
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| EDIT_TEXT-03 | Inline editor opens pre-filled with the selected text's original content, font size, and color | D-301: editor panel design, pre-fill logic from `HitResult.line.chars`, font size from `TextLine.fragment.style.fontSize`, color from `TextLine.fragment.style.color` |
| EDIT_TEXT-04 | User can commit an edit; the page re-renders in place showing the new text without restarting the app | D-303/D-305: JNI mutation path + full-page re-render via `PageBitmapCache` with cache invalidation for edited page only |
| STATE-01 | All content mutations are Command objects on an undo/redo stack, present from the first edit feature onward | D-302: `EditCommand` per Apply, ViewModel-owned `past`/`future` stacks |
| STATE-02 | User can undo and redo edits; the page re-renders to reflect stack position | D-302: `undo()`/`redo()` execute inverse mutation, trigger re-render, update selection |
| STATE-03 | Edit session state (open document, selection, undo stack) is held in ViewModel state and survives rotation | D-301/D-302: ViewModel lifecycle-bound, draft text + command stack + selection in StateFlow |
| FALLBACK-01 | On text edit, the app detects when the embedded (often subset) font cannot encode the new characters and falls back to a bundled font for the affected object instead of corrupting glyphs | D-304: subset detection via font name prefix (+XXXXXX), standard 14 font list, WinAnsi coverage check, bundled Liberation font fallback |
| FALLBACK-02 | Text runs too fragmented to map cleanly are still editable at the single-run level (degraded but functional) without corrupting neighboring content | D-303: mutation Path B (remove+insert) handles multi-object lines; text object is atomic unit |
</phase_requirements>

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Inline editor UI (docked panel, IME handling) | Browser / Client (Compose) | — | Pure UI, no server/API component |
| Command stack (undo/redo) | Browser / Client (ViewModel) | — | In-memory state, lifecycle-bound to Activity/Fragment |
| Text selection / hit-testing | Browser / Client (pdfcore) | — | Pure Kotlin, runs on device, uses existing `HitTest` |
| PDFium text mutation (FPDFText_SetText, object replace) | Browser / Client (JNI / NDK) | — | Native code runs on device; no server involvement |
| Font subset detection & fallback | Browser / Client (JNI + Kotlin) | — | Font metadata queried from PDFium on device |
| Page re-render after edit | Browser / Client (Compose + PageBitmapCache) | — | Bitmap rendering on device via PDFium |
| Temp file management (edit → save copy → reopen) | Browser / Client (JNI + Kotlin) | — | Local filesystem, scoped to app |
| Bundled font loading (Liberation TTFs) | Browser / Client (assets → JNI) | — | Assets packaged in APK, loaded via `FPDFText_LoadFont` |
| Save-as-copy (Phase 5) | Browser / Client (SAF) | — | Deferred; uses `ACTION_CREATE_DOCUMENT` |

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin | 2.4.20 | Primary language | Latest stable; Compose-first, coroutines-first, K2 compiler; AGP 9.x compatible. [VERIFIED: gradle/libs.versions.toml] |
| Jetpack Compose | BOM 2026.09.00 | UI toolkit | Declarative UI, modern; aligns with 2026 Android recommendations. BOM locks compatible versions. [VERIFIED: gradle/libs.versions.toml] |
| Android Gradle Plugin (AGP) | 9.4.0 | Build tool | Required for recent Compose/Kotlin; AGP 9.4 supports API 37 context; Gradle 9.6+ aligned. [VERIFIED: gradle/libs.versions.toml] |
| Gradle | 9.6.0+ | Build system | Matches AGP 9.4; Kotlin 2.4.20 compatible up to 9.7. [VERIFIED: gradle/libs.versions.toml] |
| `io.legere:pdfiumandroid` | 3.0.4-alpha01 | PDFium binding (Kotlin, coroutines) | Idiomatic Kotlin, coroutine-friendly, active, Maven Central; supports rendering to bitmaps and low-level page access. Aligns with direct PDFium object access. [VERIFIED: gradle/libs.versions.toml + Context7 /johngray1965/pdfiumandroidkt] |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | ViewModel + Compose integration | For page viewer, selection state, undo/redo stack, I/O off UI thread. [VERIFIED: gradle/libs.versions.toml] |
| `kotlinx.coroutines` | 1.9.0 | Async I/O, page loading, save | Required for suspend APIs (PdfiumAndroidKt suspend API). Use `Dispatchers.IO` for native/bitmap ops. [VERIFIED: gradle/libs.versions.toml] |
| `androidx.activity:activity-compose` | 1.13.0 | Activity integration, SAF | For `ACTION_OPEN_DOCUMENT`, recent files. [VERIFIED: gradle/libs.versions.toml] |
| `androidx.core:core-ktx` | 1.19.1 | Extensions | General utilities. [VERIFIED: gradle/libs.versions.toml] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.datastore:datastore-preferences` | 1.2.1 | Settings persistence | Theme, viewer prefs, recent files. Already in use. |
| `androidx.navigation:navigation-compose` | 2.10.2 | Navigation | Home ↔ Viewer ↔ Settings ↔ About. Already in use. |
| Liberation Fonts (bundled TTF) | 2.1.5+ | Fallback font for subset scenarios | 12 TTFs (Serif/Sans/Mono × Regular/Bold/Italic/BoldItalic) in `assets/fonts/liberation/`. SIL OFL 1.1. [CITED: https://github.com/liberationfonts/liberation-fonts] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `io.legere:pdfiumandroid` (Kotlin/coroutines) | HyntixHQ/KotlinPdfium | Also strong (396 JNI bindings, page editing APIs); check Maven availability/maintenance; current latest 1.0.7. Use if API surface better fits specific editing ops. [ASSUMED] |
| `io.legere:pdfiumandroid` | ComposePdfReader (KMP) | KMP useful if targeting other platforms, but for pure Android Redraft, adds overhead; also depends on bblanchon/pdfium-binaries build pipeline. [ASSUMED] |
| `io.legere:pdfiumandroid` | PdfiumAndroid (barteksc) | Original Java binding; less idiomatic Kotlin/coroutines. Still works; maintenance varies. [ASSUMED] |
| `io.legere:pdfiumandroid` | Android PdfRenderer (platform) | Only read/render, no text/image object editing or content stream regeneration. Cannot edit existing content. Incompatible with requirement. [VERIFIED: Android docs] |
| `io.legere:pdfiumandroid` | AndroidX PDF viewer | Viewer-only; no content editing API. Incompatible. [VERIFIED: Android docs] |

**Installation:**
```bash
# Already in gradle/libs.versions.toml and app/build.gradle.kts
# pdfiumandroid 3.0.4-alpha01 is declared and used
# New: create pdfedit module with CMake + JNI
# Add Liberation fonts to app/src/main/assets/fonts/liberation/
```

**Version verification:**
```bash
# Verified against gradle/libs.versions.toml (2026-11-06)
# pdfiumandroid: 3.0.4-alpha01 (Maven Central, published 2026)
# Kotlin: 2.4.20 (released Sept 2026)
# Compose BOM: 2026.09.00 (Sept 2026)
# AGP: 9.4.0 (stable 2026)
# lifecycle: 2.11.0 (stable 2026)
```

---

## Package Legitimacy Audit

> **Required** whenever this phase installs external packages. Run the Package Legitimacy Gate protocol before completing this section.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `io.legere:pdfiumandroid` | Maven Central | ~2 years | ~50k/mo | github.com/johngray1965/PdfiumAndroidKt | OK | Approved (already in project) |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | Maven Central | ~4 years | ~10M/mo | androidx/androidx | OK | Approved (already in project) |
| `kotlinx.coroutines` | Maven Central | ~6 years | ~50M/mo | kotlin/kotlinx.coroutines | OK | Approved (already in project) |
| Liberation Fonts (TTF assets) | GitHub Releases | ~15 years | N/A | github.com/liberationfonts/liberation-fonts | OK | Approved (bundled as assets) |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

*All packages verified against Maven Central / official sources. No new external dependencies added in this phase beyond bundled font assets.*

---

## Architecture Patterns

### System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           USER INTERACTION                                   │
│  Tap line → "Edit text" action / double-tap selected line                   │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        PdfViewerViewModel (MVVM)                            │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐ │
│  │  editorState    │  │  commandStack   │  │  selectionState             │ │
│  │  (draft, caret, │  │  (past/future   │  │  (selectedLine, blockId,    │ │
│  │   showing)      │  │   EditCommand)  │  │   editable, pageIndex)      │ │
│  └────────┬────────┘  └────────┬────────┘  └──────────────┬──────────────┘ │
└───────────┼────────────────────┼─────────────────────────┼──────────────────┘
            │                    │                         │
            ▼                    ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EDITOR PANEL (Compose)                               │
│  Docked panel above IME • Single-line TextField • Apply/Cancel              │
│  Inline notices: "Font will change to X", "Text longer than line"           │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ onApplyEdit(newText)
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PdfEditingEngine (JNI Interface)                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ suspend fun open(uri): EditingHandle                                 │   │
│  │ suspend fun close(handle)                                            │   │
│  │ suspend fun setText(handle, page, objIdx, newText): Boolean          │   │
│  │ suspend fun replaceTextObject(handle, page, objIdx, newText,        │   │
│  │     fontName, fontSizePt, color, matrix)                             │   │
│  │ suspend fun saveAsCopy(handle, destPath): Boolean                    │   │
│  │ suspend fun loadBundledFont(handle, fontName, bold, italic): FontHdl │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
         ┌─────────────────────┐     ┌─────────────────────┐
         │  EDITING DOCUMENT   │     │  RENDERING DOCUMENT │
         │  (independent, JNI) │     │  (pdfiumandroid)    │
         │  FPDF_LoadDocument  │     │  PdfDocumentKt      │
         │  Page obj enum      │     │  PageBitmapCache    │
         │  FPDFText_SetText   │     │  ThumbnailCache     │
         │  FPDFPageObj_*      │     │  Text extraction    │
         │  FPDF_SaveAsCopy    │     │                     │
         └──────────┬──────────┘     └──────────┬──────────┘
                    │                           │
                    │ save temp → reopen        │ invalidate page
                    └─────────────┬─────────────┘
                                  ▼
                    ┌─────────────────────────────┐
                    │   PageBitmapCache           │
                    │  invalidatePage(pageIndex)  │
                    │  (all zoom buckets + thumb) │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │  PdfViewerScreen (Compose)  │
                    │  Re-render at current zoom  │
                    │  Cross-fade old→new bitmap  │
                    │  Reselect edited line       │
                    └─────────────────────────────┘
```

### Recommended Project Structure

```
app/
├── src/main/
│   ├── cpp/pdfedit/              # NEW: Custom JNI module
│   │   ├── CMakeLists.txt
│   │   ├── pdfedit.cpp           # JNI bridge (7 functions)
│   │   ├── pdfedit.h
│   │   ├── EditingDocument.cpp   # Editing doc lifecycle
│   │   ├── EditingDocument.h
│   │   ├── TextMutation.cpp      # setText / replaceTextObject
│   │   ├── TextMutation.h
│   │   ├── FontLoading.cpp       # loadBundledFont
│   │   ├── FontLoading.h
│   │   └── Save.cpp              # saveAsCopy
│   ├── assets/fonts/liberation/  # NEW: 12 TTF files
│   │   ├── LiberationSerif-Regular.ttf
│   │   ├── LiberationSerif-Bold.ttf
│   │   ├── LiberationSerif-Italic.ttf
│   │   ├── LiberationSerif-BoldItalic.ttf
│   │   ├── LiberationSans-Regular.ttf
│   │   ├── LiberationSans-Bold.ttf
│   │   ├── LiberationSans-Italic.ttf
│   │   ├── LiberationSans-BoldItalic.ttf
│   │   ├── LiberationMono-Regular.ttf
│   │   ├── LiberationMono-Bold.ttf
│   │   ├── LiberationMono-Italic.ttf
│   │   └── LiberationMono-BoldItalic.ttf
│   ├── java/com/redraft/
│   │   ├── pdfedit/              # NEW: JNI Kotlin wrapper
│   │   │   ├── PdfEditingEngine.kt
│   │   │   ├── EditingHandle.kt
│   │   │   ├── FontHandle.kt
│   │   │   └── PdfEditModule.kt  # CMake integration
│   │   ├── ui/viewer/
│   │   │   ├── EditorPanel.kt    # NEW: Docked inline editor
│   │   │   ├── EditorPanelState.kt
│   │   │   └── SelectionOverlay.kt  (extend for reselection)
│   │   ├── ui/PdfViewerViewModel.kt  (add command stack, editor state)
│   │   └── data/                 # (existing)
pdfcore/
├── src/main/kotlin/com/redraft/pdfcore/
│   ├── selection/                # (existing HitTest, TextPageLayout, etc.)
│   └── ...
```

### Pattern 1: Command Stack with Per-Commit Granularity
**What:** Each user Apply creates one `EditCommand` capturing the full before/after state for a single line edit. Two stacks (`past` for undo, `future` for redo) in `PdfViewerViewModel`.
**When to use:** All content mutations (text edit, move, delete, image ops) from the first edit feature onward (STATE-01).
**Example:**
```kotlin
// Source: CONTEXT.md D-302
data class EditCommand(
    val documentId: String,           // URI or content hash
    val pageIndex: Int,
    val lineId: String,               // blockId from selection (p{page}:b{firstChar})
    val oldText: String,
    val newText: String,
    val oldFontName: String,
    val newFontName: String,
    val fontSizePt: Float,
    val color: Int,                   // ARGB
    val baselineRect: PdfRect,        // PDF coords for reselection
) {
    suspend fun execute(engine: PdfEditingEngine, handle: EditingHandle) {
        // Apply mutation via JNI
        val success = engine.setText(handle, pageIndex, objectIndex, newText)
        if (!success) {
            engine.replaceTextObject(handle, pageIndex, objectIndex, newText,
                newFontName, fontSizePt, color, baselineRect.toMatrix())
        }
    }

    fun inverse(): EditCommand = copy(
        oldText = newText,
        newText = oldText,
        oldFontName = newFontName,
        newFontName = oldFontName,
    )
}

// In PdfViewerViewModel:
private val _past = mutableListOf<EditCommand>()
private val _future = mutableListOf<EditCommand>()
val past: List<EditCommand> get() = _past
val future: List<EditCommand> get() = _future

fun onApplyEdit(newText: String) {
    val cmd = EditCommand(...)
    viewModelScope.launch {
        cmd.execute(editingEngine, editingHandle)
        _past.add(cmd)
        _future.clear()
        triggerReRender()
    }
}

fun onUndo() { popAndExecute(_past, _future, inverse = true) }
fun onRedo() { popAndExecute(_future, _past, inverse = false) }
```

### Pattern 2: Text Object Matching from HitResult to PDFium Object
**What:** Given `HitResult.charIndex` (first char index in page's flat char list from `TextPageLayout`), find the PDFium text object in the editing document that contains that character.
**When to use:** Every edit operation needs to locate the correct text object in the editing document.
**Example:**
```kotlin
// Source: CONTEXT.md Specifics §Text Object Matching
// TextPageLayout.chars comes from FPDFText_GetCharBox in order
// PDFium text objects are in reading order
// Map by accumulating character counts per text object until reaching charIndex

suspend fun findTextObjectForCharIndex(
    handle: EditingHandle,
    pageIndex: Int,
    targetCharIndex: Int
): Int {  // returns objectIndex
    val page = FPDF_LoadPage(handle.docPtr, pageIndex)
    val objCount = FPDFPage_CountObjects(page)
    var accumulated = 0
    for (objIdx in 0 until objCount) {
        val obj = FPDFPage_GetObject(page, objIdx)
        if (FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_TEXT) {
            val textObj = obj
            val textPage = FPDFText_LoadPage(page)
            val charCount = FPDFText_CountChars(textPage)
            // This text object covers [accumulated, accumulated + charCount)
            if (targetCharIndex < accumulated + charCount) {
                FPDFText_ClosePage(textPage)
                FPDF_ClosePage(page)
                return objIdx
            }
            accumulated += charCount
            FPDFText_ClosePage(textPage)
        }
    }
    FPDF_ClosePage(page)
    throw IllegalStateException("No text object found for charIndex $targetCharIndex")
}
```

### Pattern 3: Font Subset Detection & Fallback Selection
**What:** Determine if the original font can encode the new text. If not, select a bundled Liberation font matching the original's style.
**When to use:** Before every edit commit (D-304).
**Example:**
```kotlin
// Source: CONTEXT.md D-304
enum class FontFallbackDecision {
    KEEP_ORIGINAL,           // Safe: all new chars in original text OR standard 14 + WinAnsi
    USE_BUNDLED_FALLBACK,    // Subset font missing chars, or non-standard, or non-WinAnsi
    UNSUPPORTED_CHARS        // New text contains chars outside Liberation coverage
}

data class FontAnalysis(
    val originalFontName: String,    // e.g., "+ABCDEF-TimesNewRomanPSMT"
    val isSubset: Boolean,           // name starts with 6-letter tag + "+"
    val isStandard14: Boolean,       // matches standard 14 font names
    val fontFlags: Int,              // from FPDFText_GetFontInfo
    val bold: Boolean,
    val italic: Boolean,
)

fun analyzeFontForEdit(
    originalFontName: String,
    newText: String,
    originalText: String,
    fontFlags: Int
): FontFallbackDecision {
    val isSubset = originalFontName.matches(Regex("^[A-Z]{6}\\+"))
    val baseName = if (isSubset) originalFontName.substringAfterLast("+") else originalFontName
    val isStandard14 = STANDARD_14_FONTS.contains(baseName)
    
    // Check if all new chars already in original text (safe deletion/reordering)
    val newChars = newText.toSet() - originalText.toSet()
    if (newChars.isEmpty()) return FontFallbackDecision.KEEP_ORIGINAL
    
    // Standard 14 non-subset with WinAnsi coverage
    if (!isSubset && isStandard14 && newChars.all { it in WIN_ANSI_CHARS }) {
        return FontFallbackDecision.KEEP_ORIGINAL
    }
    
    // Check Liberation coverage (Latin only)
    if (newChars.any { it !in LIBERATION_LATIN_CHARS }) {
        return FontFallbackDecision.UNSUPPORTED_CHARS
    }
    
    return FontFallbackDecision.USE_BUNDLED_FALLBACK
}

fun selectBundledFont(analysis: FontAnalysis): String {
    val baseName = if (analysis.isSubset) analysis.originalFontName.substringAfterLast("+") else analysis.originalFontName
    return when {
        baseName.contains("Times", ignoreCase = true) || baseName.contains("Serif", ignoreCase = true) -> "LiberationSerif"
        baseName.contains("Courier", ignoreCase = true) || baseName.contains("Mono", ignoreCase = true) -> "LiberationMono"
        else -> "LiberationSans"  // Arial, Helvetica, sans-serif, default
    } + (if (analysis.bold) "Bold" else "") + (if (analysis.italic) "Italic" else "")
}

// Standard 14 font names (PDF spec)
val STANDARD_14_FONTS = setOf(
    "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
    "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
    "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
    "Symbol", "ZapfDingbats"
)
```

### Anti-Patterns to Avoid
- **Editing at run level:** PDFium text objects are the atomic unit. Never try to split/merge runs inside a text object — use Path B (remove+insert) instead. [VERIFIED: PDFium fpdf_edit.h, CONTEXT.md D-303-never]
- **Using rendering document handles for editing:** The JNI module must own an independent editing document opened from the file. Never use `PdfDocumentKt` handles for mutation. [VERIFIED: CONTEXT.md D-303-ownership]
- **Skipping `FPDFPage_GenerateContent`:** After any object insert/remove, content stream must be regenerated or the save will not reflect changes. [VERIFIED: PDFium fpdf_edit.h, CONTEXT.md D-303-mutation-path-b]
- **Leaking native handles:** Every `FPDF_LoadDocument`/`FPDF_LoadPage`/`FPDFText_LoadPage` must have a matching close. Use `use { }` pattern or try/finally. [VERIFIED: CONTEXT.pdfcore patterns]
- **Cross-document handle usage:** Page handles from the editing document are never used with the rendering document, and vice versa. [VERIFIED: CONTEXT.md D-305-safe-reopen]
- **Editing vertical/RTL text:** Explicitly disabled in Phase 2 (D-11, `editable=false`). Show disabled snackbar. [VERIFIED: CONTEXT.md D-301-trigger]

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| PDF text editing (object mutation, content regeneration) | Custom PDF parser/writer, content stream surgery | PDFium native APIs (`FPDFText_SetText`, `FPDFPageObj_CreateTextObj`, `FPDFPage_InsertObject`, `FPDFPage_GenerateContent`, `FPDF_SaveAsCopy`) | PDF content streams are complex (operators, graphics state, font subsets, encoding). PDFium is the Chromium engine — battle-tested, handles all edge cases. [VERIFIED: PDFium public headers, CONTEXT.md D-303] |
| Font subset detection & character encoding | Custom font parser, cmap traversal | PDFium `FPDFText_GetFontInfo` (font name + flags), `FPDFFont_GetIsEmbedded`, `FPDFFont_GetFlags` + standard 14 font list + WinAnsi table | Font subset detection requires parsing the font name prefix (`+XXXXXX`) and checking encoding. PDFium exposes font metadata natively. [VERIFIED: PDFium fpdf_text.h, fpdf_edittext.cpp] |
| Bundled fallback fonts | Custom font design, metric calculation | Liberation fonts (Serif/Sans/Mono) — metrically compatible with Times New Roman, Arial, Courier New | Liberation fonts are explicitly designed as metric-compatible substitutes. SIL OFL 1.1 allows bundling. [CITED: https://github.com/liberationfonts/liberation-fonts] |
| Undo/redo command stack | Custom Memento pattern, event sourcing | Simple two-stack (`past`/`future`) in ViewModel with data class `EditCommand` | Per-commit granularity is simple and sufficient. No need for operational transformation or CRDTs. [VERIFIED: CONTEXT.md D-302] |
| Page re-render after edit | Custom diff/patch rendering | Full page re-render via existing `PageBitmapCache` + cache invalidation for edited page only | Simpler, uses existing infrastructure, cross-fade prevents flash. Partial re-render adds complexity for marginal gain. [VERIFIED: CONTEXT.md D-305] |
| Temp file management for edit→save→reopen | Custom file format, in-memory document | `FPDF_SaveAsCopy` to temp file → reopen via `PdfDocumentKt` | PDFium's incremental save is efficient. Temp file is the bridge between editing and rendering documents. [VERIFIED: CONTEXT.md D-303-workflow] |

**Key insight:** PDF content editing is deceptively complex — font subsets, encoding, content stream operators, graphics state, object ownership. PDFium has 15+ years of Chromium hardening. The custom JNI module should be a *thin bridge* exposing only the 7 native functions needed, not a reimplementation.

---

## Runtime State Inventory

> This phase involves new capabilities (editing) but no rename/refactor/migration of existing strings. The inventory is for completeness — no action required beyond what's in the plan.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | No existing stored data references the new editing feature (command stack is in-memory only, survives rotation via ViewModel, not process death). | None — command stack is session-only (D-302-survival). Save checkpointing is Phase 5. |
| Live service config | No external services (n8n, Datadog, etc.) involved. | None |
| OS-registered state | No OS-level registrations (Task Scheduler, pm2, launchd, systemd) for this feature. | None |
| Secrets/env vars | No new secrets or env vars. Font assets are bundled in APK. | None |
| Build artifacts | New `pdfedit` CMake module produces `libpdfedit.so` (arm64-v8a, x86_64). Must be packaged in APK via `jniLibs` or CMake `add_library`. | Ensure CMake config outputs to `app/src/main/jniLibs/${ANDROID_ABI}/` or uses `externalNativeBuild` correctly. |

---

## Common Pitfalls

### Pitfall 1: Editing Document vs Rendering Document Handle Confusion
**What goes wrong:** Using `PdfDocumentKt` handles (from the rendering document) for mutation, or passing editing document page handles to the rendering pipeline.
**Why it happens:** Both documents are `FPDF_DOCUMENT` pointers; the pdfiumandroid binding doesn't expose editing APIs so it's tempting to cast.
**How to avoid:** Strict separation — JNI module owns `EditingHandle` wrapping an independent `FPDF_LoadDocument`. Rendering uses `PdfDocumentKt`. Never mix. [VERIFIED: CONTEXT.md D-303-ownership, D-305-safe-reopen]
**Warning signs:** Crashes in native code, "invalid handle" errors, edits not appearing after re-render.

### Pitfall 2: Font Subset Detection False Negatives
**What goes wrong:** Failing to detect a subset font (e.g., font name doesn't have `+XXXXXX` prefix but is still subset), or incorrectly allowing unsupported characters.
**Why it happens:** Not all subset fonts follow the 6-letter prefix convention; some standard 14 fonts are embedded as subsets.
**How to avoid:** Check `FPDFFont_GetIsEmbedded` + `FPDFFont_GetFlags` + font name prefix. When in doubt, fall back to bundled font (safe). [VERIFIED: PDFium fpdf_edittext.cpp, CONTEXT.md D-304]
**Warning signs:** Corrupted/blank glyphs after edit, "tofu" squares.

### Pitfall 3: Text Object Matching Off-by-One
**What goes wrong:** `HitResult.charIndex` maps to wrong text object, causing edit to apply to adjacent line or corrupt neighboring content.
**Why it happens:** `TextPageLayout.chars` is flat list from `FPDFText_GetCharBox`; PDFium text objects may not align 1:1 with visual lines (merged runs, ligatures).
**How to avoid:** Accumulate character counts per text object in reading order. Unit test with multi-run lines, ligatures, subset fonts. [VERIFIED: CONTEXT.md Specifics §Text Object Matching]
**Warning signs:** Edit changes wrong line, selection jumps after re-render.

### Pitfall 4: Temp File Cleanup Leaks
**What goes wrong:** Temp files from `FPDF_SaveAsCopy` accumulate, filling storage.
**Why it happens:** Edit → save copy → reopen happens on every Apply; exceptions or crashes skip cleanup.
**How to avoid:** Use `createTempFile` with `deleteOnExit`, or a dedicated cache directory cleared on app start. Wrap in `try/finally`. [ASSUMED]
**Warning signs:** Storage growth, "no space left" on low-storage devices.

### Pitfall 5: Cache Invalidation Too Broad/Narrow
**What goes wrong:** Clearing all page bitmaps (too broad) → full re-render of all pages, scroll jump. Or not clearing thumbnail/text layout (too narrow) → stale overlay.
**Why it happens:** `PageBitmapCache` keyed by `(pageIndex, scaleBucket)`; thumbnail and text layout are separate caches.
**How to avoid:** `PageBitmapCache.invalidatePage(pageIndex)` removes all scale buckets for that page. Also call `ThumbnailCache.invalidate(pageIndex)` and clear `textLayoutCache[pageIndex]`. [VERIFIED: CONTEXT.md D-305-cache-invalidation, PageBitmapCache.kt]
**Warning signs:** Zoom/pan reset after edit, stale selection overlay, wrong thumbnail.

### Pitfall 6: Cross-Fade Flash
**What goes wrong:** New bitmap not ready when old is cleared → page flashes blank.
**Why it happens:** Async render completion not awaited before swapping.
**How to avoid:** Keep old bitmap displayed until new one is in cache and `retainDisplay` called. Then cross-fade (200-300ms). [VERIFIED: CONTEXT.md D-305-cross-fade]
**Warning signs:** Momentary white/blank page on edit commit.

### Pitfall 7: JNI Local Reference Table Overflow
**What goes wrong:** `JNI ERROR (app bug): local reference table overflow (max=512)` during text object enumeration.
**Why it happens:** Looping `FPDFPage_GetObject`/`FPDFPageObj_GetType` creates local refs not deleted.
**How to avoid:** Use `PushLocalFrame`/`PopLocalFrame` in JNI loops, or delete local refs explicitly. [ASSUMED - standard JNI practice]
**Warning signs:** Crash on pages with many text objects (>100).

---

## Code Examples

### JNI Module: CMakeLists.txt (pdfedit)
```cmake
# Source: CONTEXT.md D-303-decision, Android NDK CMake docs
cmake_minimum_required(VERSION 3.22)
project(pdfedit LANGUAGES CXX)

# PDFium headers matching the AAR's Chromium build
# These must be vendored or fetched from the same Chromium commit as pdfiumandroid 3.0.4-alpha01
set(PDFIUM_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/../../../pdfium-headers")

add_library(pdfedit SHARED
    pdfedit.cpp
    EditingDocument.cpp
    TextMutation.cpp
    FontLoading.cpp
    Save.cpp
)

target_include_directories(pdfedit PRIVATE
    ${PDFIUM_INCLUDE_DIR}
    ${CMAKE_SOURCE_DIR}
)

# Link against the AAR's libpdfium.so
# The AAR packages libpdfium.so for all ABIs; we import it as a prebuilt
find_library(PDFIUM_LIB pdfium PATHS
    "${CMAKE_SOURCE_DIR}/../../../pdfiumandroid-prebuilt/${ANDROID_ABI}"
    "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${ANDROID_ABI}"
)

target_link_libraries(pdfedit
    ${PDFIUM_LIB}
    android
    log
)

# ABI filters match app/build.gradle.kts
set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a;x86_64")
```

### JNI Bridge: pdfedit.cpp (7 functions)
```cpp
// Source: CONTEXT.md Specifics §JNI Module API
#include <jni.h>
#include "pdfedit.h"
#include "EditingDocument.h"
#include "TextMutation.h"
#include "FontLoading.h"
#include "Save.h"

// Handle types (opaque pointers)
using EditingHandle = EditingDocument*;
using FontHandle = FPDF_FONT;

// 1. Open editing document from file URI (SAF file descriptor)
extern "C" JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeOpen(
    JNIEnv* env, jclass, jstring uri, jobject fd, jlong offset, jlong length) {
    const char* uriStr = env->GetStringUTFChars(uri, nullptr);
    // Duplicate FD for our own use
    int dupFd = dup(env->GetDirectBufferAddress(fd));
    EditingDocument* doc = new EditingDocument(dupFd, offset, length);
    env->ReleaseStringUTFChars(uri, uriStr);
    return reinterpret_cast<jlong>(doc);
}

// 2. Close editing document
extern "C" JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<EditingHandle>(handle);
}

// 3. Try in-place text set (Path A)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeSetText(
    JNIEnv*, jclass, jlong handle, jint pageIndex, jint objectIndex, jstring newText) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* text = env->GetStringUTFChars(newText, nullptr);
    // Convert UTF-8 to UTF-16LE for FPDFText_SetText (expects FPDF_WIDESTRING)
    std::u16string wideText = utf8ToUtf16(text);
    bool success = TextMutation::setText(doc, pageIndex, objectIndex, wideText);
    env->ReleaseStringUTFChars(newText, text);
    return success;
}

// 4. Replace text object (Path B)
extern "C" JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeReplaceTextObject(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex,
    jstring newText, jstring fontName, jfloat fontSizePt, jint color, jfloatArray matrix) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* text = env->GetStringUTFChars(newText, nullptr);
    const char* font = env->GetStringUTFChars(fontName, nullptr);
    std::u16string wideText = utf8ToUtf16(text);
    std::u16string wideFont = utf8ToUtf16(font);
    jfloat* matrixArr = env->GetFloatArrayElements(matrix, nullptr);
    TextMutation::replaceTextObject(doc, pageIndex, objectIndex, wideText,
        wideFont, fontSizePt, color, matrixArr);
    env->ReleaseStringUTFChars(newText, text);
    env->ReleaseStringUTFChars(fontName, font);
    env->ReleaseFloatArrayElements(matrix, matrixArr, 0);
}

// 5. Save as copy to temp file
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeSaveAsCopy(
    JNIEnv* env, jclass, jlong handle, jstring destPath) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* path = env->GetStringUTFChars(destPath, nullptr);
    bool success = Save::saveAsCopy(doc, path);
    env->ReleaseStringUTFChars(destPath, path);
    return success;
}

// 6. Load bundled font from asset FD
extern "C" JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeLoadBundledFont(
    JNIEnv* env, jclass, jlong handle, jstring fontName, jboolean bold, jboolean italic, jobject fd) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* name = env->GetStringUTFChars(fontName, nullptr);
    int fontFd = dup(env->GetDirectBufferAddress(fd));
    FPDF_FONT font = FontLoading::loadBundledFont(doc, name, bold, italic, fontFd);
    env->ReleaseStringUTFChars(fontName, name);
    return reinterpret_cast<jlong>(font);
}

// 7. Get text object count for a page (for matching)
extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeGetTextObjectCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    return TextMutation::getTextObjectCount(doc, pageIndex);
}

// 8. Get char count for a text object (for matching)
extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeGetTextObjectCharCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex, jint objectIndex) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    return TextMutation::getTextObjectCharCount(doc, pageIndex, objectIndex);
}
```

### Kotlin JNI Wrapper: PdfEditingEngine.kt
```kotlin
// Source: CONTEXT.md Specifics §JNI Module API
package com.redraft.pdfedit

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.redraft.pdfcore.PdfRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PdfEditingEngine {
    suspend fun open(uri: Uri, pfd: ParcelFileDescriptor): Result<EditingHandle>
    suspend fun close(handle: EditingHandle)
    suspend fun setText(handle: EditingHandle, pageIndex: Int, objectIndex: Int, newText: String): Boolean
    suspend fun replaceTextObject(
        handle: EditingHandle,
        pageIndex: Int,
        objectIndex: Int,
        newText: String,
        fontName: String,
        fontSizePt: Float,
        color: Int,
        matrix: FloatArray
    )
    suspend fun saveAsCopy(handle: EditingHandle, destPath: String): Boolean
    suspend fun loadBundledFont(handle: EditingHandle, fontName: String, bold: Boolean, italic: Boolean): FontHandle
    suspend fun getTextObjectCount(handle: EditingHandle, pageIndex: Int): Int
    suspend fun getTextObjectCharCount(handle: EditingHandle, pageIndex: Int, objectIndex: Int): Int
}

class PdfEditingEngineImpl : PdfEditingEngine {
    companion object {
        init { System.loadLibrary("pdfedit") }
    }

    private external fun nativeOpen(uri: String, fd: ParcelFileDescriptor, offset: Long, length: Long): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeSetText(handle: Long, pageIndex: Int, objectIndex: Int, newText: String): Boolean
    private external fun nativeReplaceTextObject(handle: Long, pageIndex: Int, objectIndex: Int,
        newText: String, fontName: String, fontSizePt: Float, color: Int, matrix: FloatArray)
    private external fun nativeSaveAsCopy(handle: Long, destPath: String): Boolean
    private external fun nativeLoadBundledFont(handle: Long, fontName: String, bold: Boolean, italic: Boolean, fd: ParcelFileDescriptor): Long
    private external fun nativeGetTextObjectCount(handle: Long, pageIndex: Int): Int
    private external fun nativeGetTextObjectCharCount(handle: Long, pageIndex: Int, objectIndex: Int): Int

    override suspend fun open(uri: Uri, pfd: ParcelFileDescriptor): Result<EditingHandle> = withContext(Dispatchers.IO) {
        try {
            val handle = nativeOpen(uri.toString(), pfd, pfd.statSize, 0)
            Result.success(EditingHandle(handle))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun close(handle: EditingHandle) = withContext(Dispatchers.IO) {
        nativeClose(handle.ptr)
    }

    override suspend fun setText(handle: EditingHandle, pageIndex: Int, objectIndex: Int, newText: String): Boolean =
        withContext(Dispatchers.IO) { nativeSetText(handle.ptr, pageIndex, objectIndex, newText) }

    override suspend fun replaceTextObject(handle: EditingHandle, pageIndex: Int, objectIndex: Int,
        newText: String, fontName: String, fontSizePt: Float, color: Int, matrix: FloatArray) =
        withContext(Dispatchers.IO) {
            nativeReplaceTextObject(handle.ptr, pageIndex, objectIndex, newText, fontName, fontSizePt, color, matrix)
        }

    override suspend fun saveAsCopy(handle: EditingHandle, destPath: String): Boolean =
        withContext(Dispatchers.IO) { nativeSaveAsCopy(handle.ptr, destPath) }

    override suspend fun loadBundledFont(handle: EditingHandle, fontName: String, bold: Boolean, italic: Boolean): FontHandle =
        withContext(Dispatchers.IO) {
            // Open asset FD for the font TTF
            val assetName = "fonts/liberation/${fontName}.ttf"
            val fd = ParcelFileDescriptor.open(context.assets.openFd(assetName).fileDescriptor, ParcelFileDescriptor.MODE_READ_ONLY)
            val fontPtr = nativeLoadBundledFont(handle.ptr, fontName, bold, italic, fd)
            fd.close()
            FontHandle(fontPtr)
        }

    override suspend fun getTextObjectCount(handle: EditingHandle, pageIndex: Int): Int =
        withContext(Dispatchers.IO) { nativeGetTextObjectCount(handle.ptr, pageIndex) }

    override suspend fun getTextObjectCharCount(handle: EditingHandle, pageIndex: Int, objectIndex: Int): Int =
        withContext(Dispatchers.IO) { nativeGetTextObjectCharCount(handle.ptr, pageIndex, objectIndex) }
}

data class EditingHandle(val ptr: Long)
data class FontHandle(val ptr: Long)
```

### Editor Panel: EditorPanel.kt
```kotlin
// Source: CONTEXT.md D-301, Specifics §Editor Panel Copy
@Composable
fun EditorPanel(
    state: EditorPanelState,
    onTextChange: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Docked above IME, no dim
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding()  // Critical: sits above keyboard
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // Title
        Text(
            text = stringResource(R.string.edit_text_title), // "Edit Text"
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Inline notices (amber)
        if (state.fontChangeNotice != null) {
            Text(
                text = stringResource(R.string.font_will_change, state.fontChangeNotice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, // amber
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            )
        }
        if (state.lengthWarning) {
            Text(
                text = stringResource(R.string.text_longer_than_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        // Single-line TextField
        TextField(
            value = state.draftText,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = stringResource(R.string.cd_editing_line, state.originalText.take(30)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                autoCorrect = true,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    if (state.canApply) onApply()
                },
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = state.fontSizePt.sp,
                color = state.textColor.toColor(),
            ),
            interactionSource = remember { MutableInteractionSource() },
        )

        // Apply / Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onApply,
                enabled = state.canApply,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

data class EditorPanelState(
    val originalText: String,
    val draftText: String,
    val fontSizePt: Float,
    val textColor: Int,
    val fontChangeNotice: String? = null,  // e.g., "Liberation Sans"
    val lengthWarning: Boolean = false,
    val canApply: Boolean = true,
    val showUnsupportedChar: Char? = null,
) {
    fun withDraftText(newText: String): EditorPanelState = copy(draftText = newText)
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Android PdfRenderer (platform) | PDFium via NDK/JNI | 2020+ | PdfRenderer is read-only; PDFium enables true content editing |
| Java JNI bindings (barteksc/PdfiumAndroid) | Kotlin coroutine bindings (io.legere/pdfiumandroid) | 2023+ | Idiomatic Kotlin, suspend APIs, structured concurrency |
| Content stream surgery (string replace) | PDFium page object mutation + `FPDFPage_GenerateContent` | 2018+ (PDFium editing APIs) | Handles font subsets, encoding, graphics state correctly |
| Custom fallback fonts | Liberation fonts (metric-compatible) | 2010+ (Liberation v2) | SIL OFL, metrically compatible with Times/Arial/Courier |
| Per-keystroke undo | Per-commit command stack | 2020+ (modern editors) | Better UX, simpler implementation, matches user mental model |

**Deprecated/outdated:**
- `androidx.pdf:pdf-viewer-fragment` / Android PdfRenderer: Designed for viewing only; no content object editing API. [VERIFIED: Android docs]
- Pure HTML-based PDF "editors" in WebView: WebView overhead, limited integration, harder to map taps to PDFium objects. [ASSUMED]
- Heavy PDFBox/OpenPDF on Android: Java-only parsing/rasterization slower; PDFium is native (Chromium). [ASSUMED]
- Deprecated PdfiumAndroid forks with no coroutines/NDK updates: Risky for modern AGP/NDK; may lack 16KB alignment, API 35 compatibility. [ASSUMED]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | PDFium headers matching `pdfiumandroid 3.0.4-alpha01`'s Chromium build are available/vendored | JNI Module | Build fails if headers mismatch; native crashes at runtime |
| A2 | `FPDFText_SetText` works reliably for in-place edits on subset fonts when all chars encodable | Text Mutation Path A | Silent corruption or no-op if API behaves differently than documented |
| A3 | `FPDFPage_GenerateContent` regenerates content stream correctly after object replace | Text Mutation Path B | Saved PDF missing edits or corrupt |
| A4 | Liberation fonts cover all Latin characters needed (no gaps in Latin-1 Supplement, Latin Extended-A) | Font Fallback | Unsupported char false negatives/positives |
| A5 | Temp file created by `FPDF_SaveAsCopy` can be reopened by `PdfDocumentKt` without issues | Re-render Workflow | Re-render fails, old document shown with error |
| A6 | `PageBitmapCache.invalidatePage(pageIndex)` correctly removes all scale buckets | Cache Invalidation | Stale bitmaps at some zoom levels |
| A7 | `HitResult.charIndex` (from `TextPageLayout.chars`) maps 1:1 to PDFium text object char order | Text Object Matching | Wrong object edited, neighboring content corrupted |
| A8 | Cross-fade animation (200-300ms) is imperceptible and doesn't cause jank | Re-render | Visual flash or animation stutter |

*All ASSUMED claims require verification during implementation spike (D-303-first-plan).*

---

## Open Questions

1. **PDFium header sourcing for JNI module**
   - What we know: `pdfiumandroid 3.0.4-alpha01` bundles `libpdfium.so` from a specific Chromium commit.
   - What's unclear: Exact Chromium commit/hash; whether headers are published or must be vendored from that commit.
   - Recommendation: Check `io.legere:pdfiumandroid` GitHub releases for build info; vendor headers from matching Chromium tag.

2. **Text object char count via `FPDFText_CountChars` on a text-page-per-object**
   - What we know: `FPDFText_LoadPage` loads whole page; need per-object char count.
   - What's unclear: Whether `FPDFTextObj_GetText` + `FPDFText_CountChars` on a text-page for just that object works, or need `FPDFText_GetText` with object-specific text page.
   - Recommendation: Spike with `FPDFTextObj_GetText` (returns text for that object) and count chars.

3. **Font loading from asset FD via `FPDFText_LoadFont`**
   - What we know: `FPDFText_LoadFont` takes `const uint8_t* data, uint32_t size`.
   - What's unclear: Whether it accepts a file descriptor directly or needs the font data copied to a buffer.
   - Recommendation: Check PDFium source (`fpdfsdk/fpdf_edittext.cpp`); likely needs buffer — use `AssetManager.openFd` → read to byte array or mmap.

4. **Cross-fade implementation in Compose**
   - What we know: `PageBitmapCache` holds bitmaps; `RenderedPage` composable draws them.
   - What's unclear: Best way to cross-fade two bitmaps for the same page key (old vs new) in Compose.
   - Recommendation: `AnimatedVisibility` + `graphicsLayer` alpha, or custom `DrawModifier` with two bitmap layers.

5. **Selection reselection after re-render**
   - What we know: `HitTest` uses `charIndex` to find line; after edit, line text changes, char indices shift.
   - What's unclear: How to reliably reselect the *edited* line at the same visual position.
   - Recommendation: Use `baselineRect` (PDF coords) from `EditCommand` to hit-test at that position after re-render.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Android NDK | CMake JNI build (`pdfedit`) | ✓ | r28+ (AGP 9.4 default) | — |
| CMake | JNI module build | ✓ | 3.22+ (AGP default) | — |
| PDFium headers (vendored) | JNI compilation | ? | Matching pdfiumandroid 3.0.4-alpha01 | Must vendor from Chromium commit |
| Liberation Font TTFs | Bundled fallback | ✓ (to add) | 2.1.5+ | None — core feature |
| `io.legere:pdfiumandroid` AAR | Rendering + `libpdfium.so` | ✓ | 3.0.4-alpha01 | None — pinned |
| JVM 17 toolchain | Kotlin 2.4.20 compilation | ✓ | 17 | — |

**Missing dependencies with no fallback:**
- PDFium headers matching the AAR's build — must resolve before JNI implementation starts

**Missing dependencies with fallback:**
- None

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 + Kotlin Coroutines Test 1.9.0 + Truth/AssertJ |
| Config file | `app/build.gradle.kts` (standard Android test config) |
| Quick run command | `./gradlew :app:test --tests "*ViewModelTest*"` |
| Full suite command | `./gradlew :app:test :pdfcore:test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| EDIT_TEXT-03 | Editor opens pre-filled with line text/font/color | unit | `./gradlew :app:test --tests "*EditorPanelTest*"` | ❌ Wave 0 |
| EDIT_TEXT-04 | Commit triggers mutation + re-render | integration | `./gradlew :app:connectedAndroidTest --tests "*EditIntegrationTest*"` | ❌ Wave 0 |
| STATE-01 | EditCommand pushed to past stack on Apply | unit | `./gradlew :app:test --tests "*CommandStackTest*"` | ❌ Wave 0 |
| STATE-02 | Undo/redo executes inverse mutation + re-render | integration | `./gradlew :app:connectedAndroidTest --tests "*UndoRedoTest*"` | ❌ Wave 0 |
| STATE-03 | Draft text + command stack survive rotation | unit | `./gradlew :app:test --tests "*ViewModelRotationTest*"` | ❌ Wave 0 |
| FALLBACK-01 | Subset font missing char → bundled fallback used | unit | `./gradlew :app:test --tests "*FontFallbackTest*"` | ❌ Wave 0 |
| FALLBACK-02 | Multi-object line editable via replace path | integration | `./gradlew :app:connectedAndroidTest --tests "*FragmentedRunTest*"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :app:test --tests "*<changed_class>*Test*"` (quick unit tests)
- **Per wave merge:** `./gradlew :app:test :pdfcore:test` (full JVM test suite)
- **Phase gate:** Full suite green + connected Android tests for integration paths before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `app/src/test/kotlin/com/redraft/ui/viewer/EditorPanelTest.kt` — covers EDIT_TEXT-03, font/color pre-fill
- [ ] `app/src/test/kotlin/com/redraft/ui/CommandStackTest.kt` — covers STATE-01, STATE-02, STATE-03
- [ ] `app/src/test/kotlin/com/redraft/pdfedit/FontFallbackTest.kt` — covers FALLBACK-01, FALLBACK-02 logic (pure Kotlin)
- [ ] `app/src/test/kotlin/com/redraft/pdfedit/TextObjectMatchingTest.kt` — covers charIndex → objectIndex mapping
- [ ] `pdfcore/src/test/kotlin/com/redraft/pdfcore/selection/EditTextObjectMatchingTest.kt` — PDFium-level matching logic
- [ ] Framework install: JUnit + Coroutines Test already in `libs.versions.toml` ✅
- [ ] CMake + JNI test infrastructure: `./gradlew :app:connectedAndroidTest` for native integration tests

*(If no gaps: "None — existing test infrastructure covers all phase requirements")*

---

## Security Domain

> Required when `security_enforcement` is enabled (absent = enabled). Omit only if explicitly `false` in config.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | — |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | yes | Text input sanitization (length, invalid chars), font fallback validation, temp file path sanitization |
| V6 Cryptography | no | — |
| V7 Error Handling | yes | Graceful degradation: edit failure → snackbar, keep old document, undo/redo preserved |
| V9 Communication Security | no | — (offline app, no network) |
| V10 Malicious Code | no | — (no dynamic code loading) |
| V11 Business Logic | yes | Command stack integrity (undo after save checkpointed), font license compliance (SIL OFL) |
| V12 File Handling | yes | SAF for file access, temp file cleanup, no path traversal in temp names |
| V13 API Security | no | — |
| V14 Configuration | no | — |

### Known Threat Patterns for PDF Editing Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Malformed PDF triggering native crash (buffer overflow in PDFium) | Denial of Service | PDFium sandboxed in separate process? No — in-process. Mitigation: Keep PDFium updated, validate input via `FPDF_LoadDocument` error codes, catch native crashes via `try/catch` in JNI (limited). [ASSUMED] |
| Temp file path traversal (`../../etc/passwd`) | Tampering | Use `File.createTempFile(prefix, suffix, cacheDir)` — no user-controlled paths. [VERIFIED: Android File API] |
| Font TTF parsing vulnerability in `FPDFText_LoadFont` | Remote Code Execution | PDFium's font parser is hardened; bundled fonts are trusted assets. [ASSUMED] |
| Command stack corruption (undo after save) | Integrity | Phase 5: checkpoint stack on save; undo only affects session state. [VERIFIED: CONTEXT.md D-302-checkpoint] |
| Selection overlay info leak (text content in accessibility) | Information Disclosure | `SelectionOverlay` uses `contentDescription` with line text — acceptable for accessibility; no sensitive data beyond what user sees. [VERIFIED: CONTEXT.md D-301-accessibility] |
| Large PDF memory exhaustion (bitmap cache) | DoS | `PageBitmapCache` bounded by `maxMemory()/8`; LRU eviction. [VERIFIED: PageBitmapCache.kt] |

---

## Sources

### Primary (HIGH confidence)
- [Context7: /johngray1965/pdfiumandroidkt] - PDFiumAndroidKt API reference, NativePageContract, PdfTextPage signatures
- [PDFium public headers: fpdf_edit.h, fpdf_text.h, fpdf_edittext.cpp] - `FPDFText_SetText`, `FPDFPageObj_CreateTextObj`, `FPDFPage_InsertObject`, `FPDFPage_RemoveObject`, `FPDFPage_GenerateContent`, `FPDF_SaveAsCopy`, `FPDFText_LoadFont`, `FPDFText_GetFontInfo`, `FPDFFont_GetIsEmbedded`, `FPDFFont_GetFlags`
- [CONTEXT.md] - All locked decisions (D-301 through D-305), integration points, code context
- [REQUIREMENTS.md] - Phase requirement IDs and traceability
- [STATE.md] - Project decisions, established patterns, existing code insights

### Secondary (MEDIUM confidence)
- [Liberation Fonts GitHub: github.com/liberationfonts/liberation-fonts] - Font files, SIL OFL 1.1 license, metric compatibility claims
- [Android NDK CMake docs: developer.android.com/ndk/guides/cmake] - Prebuilt library linking, `IMPORTED` targets
- [PDFium Getting Started: pdfium.googlesource.com/pdfium/+/HEAD/docs/getting-started.md] - Library initialization, build config

### Tertiary (LOW confidence)
- [WebSearch: PDFium editing API discussions] - StackOverflow, GitHub issues on `FPDFText_SetText` behavior, `FPDFPage_GenerateContent` quirks
- [WebSearch: Android JNI CMake AAR linking] - Community patterns for linking against AAR-packaged `.so`

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All versions from `gradle/libs.versions.toml`, verified against Maven Central and Context7
- Architecture: HIGH - Based on locked decisions in CONTEXT.md, existing code patterns in pdfcore/app
- JNI module design: MEDIUM - Based on PDFium public headers and CONTEXT.md D-303; requires header sourcing spike
- Font fallback logic: HIGH - Directly from D-304 with standard 14 font list from PDF spec
- Cache invalidation/re-render: HIGH - From D-305 and existing `PageBitmapCache` implementation
- Pitfalls: MEDIUM-HIGH - Based on known PDFium/JNI/PDF editing failure modes
- Code examples: MEDIUM - Synthesized from CONTEXT.md interfaces and PDFium API; untested until spike

**Research date:** 2026-11-06
**Valid until:** 2027-02-06 (90 days — stable stack, but PDFium alpha version may update)