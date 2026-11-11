# Vendored PDFium public headers

These are the PDFium C API headers that `io.legere:pdfiumandroid-core:3.0.4-alpha01`
was built against. They are copied verbatim from the library's own source at the
matching tag, **not** from PDFium `main`:

- Source: https://github.com/johngray1965/PdfiumAndroidKt
- Tag: `3.0.4-alpha01`
- Commit: `7331dc1a3cb4e88c24947d89d7cc124677aac108`
- Path: `pdfiumandroid/core/src/main/cpp/include/`
- Files: `fpdfview.h`, `fpdf_edit.h`, `fpdf_text.h`, `fpdf_save.h`, `fpdf_ext.h`

Do not replace these with newer headers. The signatures are not stable across
PDFium versions — for example `FPDFText_LoadFont` gained a `font_type` argument
and `FPDFPageObj_GetBounds` takes four out-pointers — and a mismatch compiles
but misbehaves at runtime against the AAR's `libpdfium.so`.

PDFium is BSD-3-Clause licensed; each header carries its own copyright notice.
