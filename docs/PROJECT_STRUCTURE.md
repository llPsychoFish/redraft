# PDF Editor for Android: Project Structure

Two Gradle modules keep the native PDF engine isolated from the UI.

```
pdfedit/
├─ app/                                  # UI + app logic
│  └─ src/main/java/com/yourname/pdfedit/
│     ├─ MainActivity.kt
│     ├─ ui/
│     │  ├─ viewer/                      # PdfScreen, PageView, zoomable list
│     │  ├─ editor/                      # EditOverlay, TextEditSheet, Toolbar
│     │  └─ theme/
│     ├─ data/                           # DocumentRepository, SAF, recent files
│     ├─ editing/                        # EditSession, Command, UndoStack
│     └─ viewmodel/
├─ pdfcore/                              # PDF engine wrapper (native)
│  └─ src/main/
│     ├─ java/com/yourname/pdfcore/
│     │  ├─ PdfiumBridge.kt              # JNI declarations
│     │  ├─ PdfDocument.kt
│     │  ├─ PdfPage.kt
│     │  ├─ PageObject.kt                # sealed: TextObject, ImageObject, PathObject
│     │  └─ Coordinates.kt               # PDF <-> screen transforms
│     ├─ cpp/
│     │  ├─ CMakeLists.txt
│     │  ├─ jni_bridge.cpp
│     │  ├─ text_edit.cpp
│     │  └─ font_manager.cpp
│     └─ assets/fonts/                   # fallback fonts (Liberation, Noto)
├─ third_party/pdfium/                   # headers + prebuilt .so per ABI
├─ testdata/pdfs/                        # word, gdocs, scanned, rotated, rtl,
│                                        #   encrypted, subset-fonts
└─ docs/                                 # decisions, known limitations
```

Replace `com.yourname` with your real package name.

---

## Module responsibilities

### `app`
- **ui/viewer:** page list, zoom and scroll, page rendering views.
- **ui/editor:** selection overlay, inline text editor, toolbar.
- **data:** file access through the Storage Access Framework, recent files.
- **editing:** `EditSession` holds the open document and pending changes; `Command` + `UndoStack` implement undo/redo.
- **viewmodel:** state holders connecting UI to the editing layer.

### `pdfcore`
- **PdfiumBridge.kt:** the only place with `external fun` JNI declarations.
- **PdfDocument / PdfPage:** safe Kotlin wrappers that own native handles and close them correctly.
- **PageObject:** sealed class for text, image, and path objects with bounds and transforms.
- **Coordinates.kt:** PDF points (origin bottom-left) to screen pixels and back. Everything depends on this.
- **cpp/:** JNI glue, text editing logic, and font fallback handling.

---

## Rules to keep it clean

1. Only `pdfcore` talks to PDFium. The `app` module never sees native handles.
2. Every native handle has an owner that closes it (`Closeable` / `use {}`).
3. All edits go through a `Command` so undo/redo works for free.
4. Never mix screen coordinates into document data; store everything in PDF coordinates.
5. Every bug-causing PDF goes into `testdata/pdfs` with a test.

---

## Initial setup checklist

- [ ] Create the Android project (Kotlin, Compose, minSdk 26)
- [ ] Add the `pdfcore` library module with NDK + CMake
- [ ] Download prebuilt PDFium binaries and headers into `third_party/pdfium/`
- [ ] Configure ABI filters: `arm64-v8a`, `x86_64`
- [ ] Add a first instrumented test that loads a PDF from `testdata/pdfs`
- [ ] Write a `docs/decisions.md` file and record each major choice as you make it
