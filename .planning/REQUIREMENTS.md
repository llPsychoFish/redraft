# Requirements: Redraft

**Defined:** 2026-10-17
**Core Value:** Editing existing PDF content must actually work — true content editing (change text and images inside a PDF by editing PDFium page objects and regenerating content streams), not annotation.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### VIEWER

- [x] **VIEWER-01**: User can open a PDF via the Storage Access Framework (ACTION_OPEN_DOCUMENT)
- [x] **VIEWER-02**: User sees a recent-files list and can reopen previously opened PDFs
- [x] **VIEWER-03**: Pages render lazily (only visible + adjacent pages) via PDFium into a Compose viewer
- [x] **VIEWER-04**: User can pinch-to-zoom and pan; zoom/pan state survives rotation and config changes
- [x] **VIEWER-05**: Rendered pages use an LRU bitmap cache with explicit bitmap recycling (no unbounded memory growth)
- [~] **VIEWER-06**: Screen (touch) coordinates map correctly to PDF user space, accounting for zoom, pan, and page rotation *(zoom and pan verified; the page-rotation leg is deferred to Phase 2 - `getPageRotation()` returns a 0..3 index that is stored as `rotationDegrees`, so the quarter-turn arms of the transform are unreachable. Carried from Phase 1.)*
- [x] **VIEWER-07**: All PDFium native calls run off the main thread; document/page handles are closed deterministically (no native leaks)

### EDIT_TEXT

- [ ] **EDIT_TEXT-01**: User can tap a text object to select it; the selected object's bounding box is drawn as an overlay
- [ ] **EDIT_TEXT-02**: Adjacent text runs with the same font/size/color/direction on one baseline merge into one selectable line (fragmented-run handling); stable block identity is metadata for later expansion, and paragraph-level reflow is Phase 5
- [x] **EDIT_TEXT-03**: Inline editor opens pre-filled with the selected text's original content, font size, and color
- [x] **EDIT_TEXT-04**: User can commit an edit; the page re-renders in place showing the new text without restarting the app
- [ ] **EDIT_TEXT-05**: User can move and delete a text block

### EDIT_IMG

- [ ] **EDIT_IMG-01**: User can tap an image to select it; its bounding box is drawn as an overlay
- [ ] **EDIT_IMG-02**: User can move a selected image and see the new position after re-render
- [ ] **EDIT_IMG-03**: User can resize a selected image (corner handles) and see the new size after re-render
- [ ] **EDIT_IMG-04**: User can delete a selected image
- [ ] **EDIT_IMG-05**: User can replace a selected image with an image picked from storage, fitting the original bounds

### STATE

- [x] **STATE-01**: All content mutations (text edit, move, delete, image ops) are Command objects on an undo/redo stack, present from the first edit feature onward
- [x] **STATE-02**: User can undo and redo edits; the page re-renders to reflect stack position
- [x] **STATE-03**: Edit session state (open document, selection, undo stack) is held in ViewModel state and survives rotation

### IO

- [ ] **IO-01**: Save always writes a new copy via SAF (ACTION_CREATE_DOCUMENT); the original file is never overwritten
- [ ] **IO-02**: Save regenerates the content stream from the edited PDFium objects and validates the result by re-rendering before write
- [ ] **IO-03**: After a successful save, the undo stack is checkpointed so undo cannot corrupt the saved file's sibling state
- [ ] **IO-04**: Common errors are handled gracefully with user-visible messages: unreadable/corrupt file, encrypted/password-protected file (open rejected with message), save failure (I/O, permission revocation), scanned/image-only PDF (text edit disabled)

### FALLBACK

- [x] **FALLBACK-01**: On text edit, the app detects when the embedded (often subset) font cannot encode the new characters and falls back to a bundled font for the affected object instead of corrupting glyphs
- [x] **FALLBACK-02**: Text runs too fragmented to map cleanly are still editable at the single-run level (degraded but functional) without corrupting neighboring content

### UI

- [x] **UI-01**: User can choose the theme mode (system / light / dark); the app applies it instantly and the choice survives an app restart
- [x] **UI-02**: The app has its own brand identity — static light and dark color schemes for devices below Android 12 and for users who turn dynamic color off — plus an optional AMOLED (true-black) dark theme
- [x] **UI-03**: The launch window background matches the app theme in light and dark mode (no white flash before Compose draws)
- [x] **UI-04**: User can reach a Settings screen (Appearance, Viewer, Storage) and an About screen (app info, open-source notices, privacy statement) from Home, and every link on them works

### RELEASE

- [x] **RELEASE-01**: The app ships a release build signed with the developer's own keystore (never committed), with an incremented `versionCode` and a semantic `versionName`
- [x] **RELEASE-02**: The release APK is installed and manually verified on a real device — open, render, zoom, page overview, settings — before it is published
- [x] **RELEASE-03**: The app requests no `INTERNET` permission and works fully offline, and the About page's privacy statement matches actual behaviour
- [x] **RELEASE-04**: Support and donation links are external only (no billing library) and no feature is gated behind donating

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### EDIT_TEXT

- **EDIT_TEXT-10**: Free-form new text block insertion with manual layout
- **EDIT_TEXT-11**: Rich formatting changes (bold/italic, alignment) on existing text
- **EDIT_TEXT-12**: Text search and jump-to-page

### EDIT_IMG

- **EDIT_IMG-10**: Crop and rotate images; opacity/brightness adjustments

### DOC

- **DOC-01**: Page management — reorder, delete, insert blank pages
- **DOC-02**: PDF creation from images
- **DOC-03**: Annotation layer (highlights, freehand, shapes) distinct from content editing

### OCR

- **OCR-01**: OCR for scanned/image-only PDFs producing an editable overlay layer

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Reflow / word wrap | PDF is fixed-layout; no reflow model exists in the page. Auto-wrapping edited text would change layout semantics the user cannot control |
| Full overwrite of original file | SAF write access is fragile and revocable; overwriting risks data loss. Always save-as-copy |
| Password-protected / encrypted editing | Needs a separate key-management story; v1 rejects encrypted files with a clear message |
| OCR of scanned PDFs | Requires an ML pipeline and produces overlay edits, not true content edits; deferred to v2 |
| Multi-select of objects | Selection complexity is high; v1 is single-object selection only |
| Cross-page edits / copy-paste between PDFs | No shared clipboard document model in v1 |
| Desktop/web versions | Android-only per project constraints (minSdk 26, PDFium NDK) |
| Real-time collaboration / cloud sync | Out of product scope for a local PDF editor |
| PDF form (AcroForm) editing | Specialized object type; not part of the core text/image editing value |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| VIEWER-01 | Phase 1 | Validated |
| VIEWER-02 | Phase 1 | Validated |
| VIEWER-03 | Phase 1 | Validated |
| VIEWER-04 | Phase 1 | Validated |
| VIEWER-05 | Phase 1 | Validated |
| VIEWER-06 | Phase 1 | Partial - rotation leg deferred to Phase 2 |
| VIEWER-07 | Phase 1 | Validated |
| EDIT_TEXT-01 | Phase 2 | Pending |
| EDIT_TEXT-02 | Phase 2 | Pending |
| EDIT_TEXT-03 | Phase 3 | Complete |
| EDIT_TEXT-04 | Phase 3 | Complete |
| STATE-01 | Phase 3 | Complete |
| STATE-02 | Phase 3 | Complete |
| STATE-03 | Phase 3 | Complete |
| FALLBACK-01 | Phase 3 | Complete |
| FALLBACK-02 | Phase 3 | Complete |
| UI-01 | Phase 01.1 | Complete |
| UI-02 | Phase 01.1 | Complete |
| UI-03 | Phase 01.1 | Complete |
| UI-04 | Phase 01.1 | Complete |
| RELEASE-01 | Phase 01.1 | Complete |
| RELEASE-02 | Phase 01.1 | Complete |
| RELEASE-03 | Phase 01.1 | Complete |
| RELEASE-04 | Phase 01.1 | Complete |
| EDIT_TEXT-05 | Phase 4 | Pending |
| EDIT_IMG-01 | Phase 4 | Pending |
| EDIT_IMG-02 | Phase 4 | Pending |
| EDIT_IMG-03 | Phase 4 | Pending |
| EDIT_IMG-04 | Phase 4 | Pending |
| EDIT_IMG-05 | Phase 4 | Pending |
| IO-01 | Phase 5 | Pending |
| IO-02 | Phase 5 | Pending |
| IO-03 | Phase 5 | Pending |
| IO-04 | Phase 5 | Pending |

**Coverage:**

- v1 requirements: 34 total
- Mapped to phases: 34
- Unmapped: 0 ✓

---
*Requirements defined: 2026-10-17*
*Last updated: 2026-10-21 after inserting Phase 01.1 (UI, Settings & Release Prep) — added UI-01..UI-04 and RELEASE-01..RELEASE-04*
*Last updated: 2026-10-26 — moved EDIT_IMG-01 from Phase 2 to Phase 4, reconciling the roadmap's Phase 2 criteria with the phase's locked scope: CONTEXT D-01/D-03 keep Phase 2 text-only and read-only with an `Image` variant reserved, and 02-DISCUSSION-LOG records "Out of scope for Phase 2: any change to the document, saving, handles, images, OCR, copying text". Coverage unchanged at 34/34 mapped.*
