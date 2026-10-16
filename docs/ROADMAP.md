# PDF Editor for Android: Roadmap

**Goal:** An Android app that views PDFs and performs true content editing (changing existing text and images inside the PDF), not just annotation.

**Core approach:** Kotlin + Jetpack Compose UI on top of PDFium (via NDK/JNI) for rendering and page-object editing.

**Guiding principle:** Build the riskiest piece first. If editing text through PDFium on Android doesn't work, nothing else matters, so prove it before building UI.

---

## Why true content editing is hard

- A PDF page is a list of drawing instructions ("draw these glyphs at these coordinates"), not a document with paragraphs. There is no reflow.
- Fonts are usually subsets, containing only the characters used in the original. New characters may have no glyph in the embedded font.
- Text is often split into many small runs (kerning, per-word positioning), so one visual line can be several operators.
- Scanned PDFs contain only an image. Editing them needs OCR first, and then it is an overlay, not a true edit.

**Expectation:** Works well on clean, text-based PDFs (Word, Google Docs). Imperfect on messy ones.

---

## Phase 0: Feasibility spike (go/no-go)

- [ ] Download prebuilt PDFium Android binaries (pdfium-binaries project) instead of building PDFium from source
- [ ] Set up the NDK + CMake build and a minimal JNI bridge
- [ ] Load a PDF and a page; enumerate page objects
- [ ] Read a text object's string and bounds
- [ ] Change one text object, regenerate page content, save a copy
- [ ] Open the saved file in Chrome and at least one other viewer to confirm validity

**Exit criterion:** Edit one word in a Word-generated PDF and save a file that opens correctly everywhere.

**If it fails:** Fall back to PdfBox-Android content-stream rewriting.

---

## Phase 1: Viewer foundation

- [ ] Open files via the Storage Access Framework; recent files list
- [ ] Render pages through PDFium into bitmaps
- [ ] Lazy scrolling page list with pinch-to-zoom
- [ ] Bitmap cache with recycling (avoid out-of-memory on large files)
- [ ] Coordinate utility: PDF points (origin bottom-left) to screen pixels and back

---

## Phase 2: Text editing MVP

- [ ] Tap to select a text object; draw its bounding box as an overlay
- [ ] Inline editor with original font size and color pre-filled
- [ ] Commit the change, re-render the page, save
- [ ] Undo/redo via a command stack (build it from day one)

---

## Phase 3: Fonts and robustness

- [ ] Detect missing glyphs in subset fonts
- [ ] Fallback to a bundled substitute font (e.g. Liberation, Noto) for the edited run
- [ ] Handle rotated pages and rotated text
- [ ] Handle non-Latin and right-to-left text
- [ ] Handle encrypted and password-protected files
- [ ] Add every failing PDF to the test library

---

## Phase 4: Images and objects

- [ ] Select, move, resize, delete images
- [ ] Replace an image
- [ ] Move and delete text blocks

---

## Phase 5: Adding content and page tools

- [ ] Add new text boxes and images
- [ ] Multi-line text blocks with custom wrapping
- [ ] Page operations: rotate, delete, reorder, merge, split

---

## Phase 6: Polish and release

- [ ] Autosave and crash recovery
- [ ] Save to original or save as a copy
- [ ] Performance profiling on a low-end device
- [ ] Optional OCR (ML Kit) for scanned files, as an overlay
- [ ] Play Store preparation

---

## Key decisions

| Decision | Choice |
|---|---|
| Language / UI | Kotlin + Jetpack Compose; page drawn in a custom Canvas |
| PDF engine | PDFium (BSD/Apache licensed) via NDK + JNI |
| minSdk | 26 |
| ABIs | `arm64-v8a`, plus `x86_64` for the emulator |
| Editing model | Edit PDFium page objects directly; regenerate content on save; no parallel document model |
| Native headers to study | `fpdf_edit.h`, `fpdf_save.h` (check exact function names for your downloaded version) |

---

## Testing strategy

- Keep a `testdata/pdfs` library: Word, Google Docs, scanned, rotated, RTL, encrypted, subset-font files.
- Instrumented test per file: open, edit, save, re-render, compare against a reference image.
- Always verify output in a second viewer. PDFium tolerates files that other readers reject.

---

## Open question

What kind of PDFs matter most: text-based (Word/Google Docs) or scanned? This decides how much risk is taken on in Phases 3 and 6.
