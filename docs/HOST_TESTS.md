# Host tests for the native editor

`tools/native-host-test/` runs the app's real C++ (`app/src/main/cpp/pdfedit`) on a PC
against a real PDFium build, so a native change can be checked in seconds.

## Setup

```bash
python3 -m venv /tmp/redraft-hostvenv
/tmp/redraft-hostvenv/bin/pip install pypdfium2 reportlab
PATH=/tmp/redraft-hostvenv/bin:$PATH bash tools/native-host-test/run_host_tests.sh
```

Needs `g++` and the DejaVu fonts (`/usr/share/fonts/truetype/dejavu/`). A system-wide
`pip install` is refused on Debian/Ubuntu (PEP 668), hence the venv.

## What it covers

- `mkpdfs.py` writes `A_single.pdf` (each line is ONE text object) and `B_perword.pdf`
  (each WORD is its own text object).
- `line_edit_test.cpp` edits the heading through `TextMutation::editLine`, saves a copy,
  re-reads it and asserts the new text is present, the old heading is gone, and the next
  line is unchanged. It also asserts the editor's start text is
  `KOFORIDUA TECHNICAL UNIVERSITY` and that a mismatched line is refused.
- `edit_one_object_test.cpp` exercises the older single-object `findTextObject` path.

## Limitation

The PC build of PDFium can substitute a system font for a missing glyph, so this loop
cannot prove how a replaced line renders on Android. Font fallback still needs a phone
check.
