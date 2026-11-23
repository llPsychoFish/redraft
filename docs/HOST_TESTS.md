# Native editing host tests

The app's C++ editing code (`app/src/main/cpp/pdfedit`) can be exercised on a PC
against a real PDFium build, without installing the app on a phone. This catches
regressions in the text-mutation logic (line splitting, font fallback, save,
reopen) in seconds.

## Files

| File | Purpose |
|------|---------|
| `tools/native-host-test/run_host_tests.sh` | Builds all test binaries and runs every check |
| `tools/native-host-test/mkpdfs.py` | Generates `A_single.pdf` (one object per line) and `B_perword.pdf` (one object per word) |
| `tools/native-host-test/tricky_layouts_mk.py` | Generates `tricky.pdf` with ten tricky line layouts (see below) |
| `tools/native-host-test/edit_one_object_test.cpp` | Exercises Path A (`FPDFText_SetText`) on a single-object line |
| `tools/native-host-test/edit_multi_object_prototype.cpp` | Prototype of multi-object line editing |
| `tools/native-host-test/line_edit_test.cpp` | Calls `findTextObject` + `setText`/`replaceTextObject` + `saveAsCopy` |
| `tools/native-host-test/replacement_edit_test.cpp` | Exercises Path B (replacement object with bundled font) |
| `tools/native-host-test/tricky_layouts_test.cpp` | Runs `editLine` on each of the ten tricky layouts and checks the result |

## Tricky layouts (tricky_layouts_mk.py)

Ten line layouts that occur in real Word/export PDFs. All ten are expected to
edit successfully (result "OK"):

| # | Layout | What it exercises |
|---|--------|-------------------|
| 0 | One text object per line | Simple baseline |
| 1 | One object per word | Common in exported headings |
| 2 | Non-breaking spaces (U+00A0) | Very common from Word |
| 3 | Ligatures (fi, fl, ffi) | Glyphs that merge into one code point |
| 4 | Superscript as separate raised object | "6th" with raised "th" |
| 5 | Faux bold (text drawn twice) | Same text at tiny offset |
| 6 | One object per character | Worst-case fragmentation |
| 7 | Letter-spaced heading | `Tj` with char spacing |
| 8 | Two columns on one baseline | Select left column only |
| 9 | Bold word inside a sentence | Mixed fonts on one line |

## Running

```bash
bash tools/native-host-test/run_host_tests.sh
```

Needs: `g++`, `python3`, and `pip install pypdfium2 reportlab`. The DejaVu
fonts at `/usr/share/fonts/truetype/dejavu/` must be present.

## Expected output

All host tests pass: every `editLine` call returns `LINE_EDIT_OK`, every
`saveAsCopy` returns true, and every tricky-layout comparison matches the
expected text.
