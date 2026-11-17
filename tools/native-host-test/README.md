# Native editing: host test loop

Purpose: check the app's C++ editing code (`app/src/main/cpp/pdfedit`) on your PC in seconds, against a real PDFium build, without installing the app on a phone.

## Files
- `mkpdfs.py`: generates two PDFs with embedded fonts. `A_single.pdf` draws each line as ONE text object. `B_perword.pdf` draws each WORD as its own text object (many real PDFs are built like this).
- `edit_one_object_test.cpp`: calls the app's own `findTextObject`, `setText`, `replaceTextObject` and `saveAsCopy`.
- `edit_multi_object_prototype.cpp`: a tested prototype of editing a line made of several text objects (keep the left-most object, give it the whole new text, remove the others).
- `run_host_tests.sh`: builds both programs.

## Results I got (real PDFium, subset fonts)
| PDF | Line | App's current code | Prototype |
|---|---|---|---|
| A_single | "KOFORIDUA TECHNICAL UNIVERSITY" to "Arnold Sampson" | finds 1 object, edit saved, text re-read correctly | not needed |
| A_single | a full sentence | same, works | not needed |
| B_perword | heading (3 objects) | `findTextObject` returns -1 (no single object matches) | edit saved, text re-read correctly |
| B_perword | sentence (11 objects) | `findTextObject` returns -1 | edit saved |

## Caveat
This host PDFium can substitute a system font when a glyph is missing, so it does not prove that an edit with letters missing from a real subset font renders correctly on Android. Keep the font-fallback check in the app and test that on a phone.
