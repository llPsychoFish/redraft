#!/usr/bin/env python3
"""Deterministically generate the Phase 2 test fixtures (D-33).

Two families of fixtures are written into ``app/src/androidTest/assets``:

* The four coordinate-contract fixtures used by ``TransformOracleTest``
  (``text_basic``, ``rotated90``, ``rotated270``, ``cropbox_offset``).  They are
  produced byte-identically to the versions committed with plan 02-01.
* The seven merge / block fixtures of plan 02-02 (``two_columns``, ``table``,
  ``list_bulleted``, ``list_lettered``, ``justified``, ``fragmented_runs``,
  ``scan_only``).

Every fixture gets a ``<name>.expected.json`` sidecar with per-line
``{text, originPt, widthPt, heightPt, charRange}`` computed from the
generator's own text placement, so the instrumented tests assert exact numbers
instead of eyeballing a rendering.  Running the script twice produces
byte-identical files: nothing in it depends on the clock, the locale or the
dictionary order.

Every fixture is synthetic — no real-world document ever enters the repository.

Run from the repo root: python3 tools/gen_sample_pdf.py
"""

import json
import os

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(REPO_ROOT, "app", "src", "androidTest", "assets")

PAGE_W, PAGE_H = 612, 792  # US Letter in points

# The four coordinate-contract fixtures (plan 02-01).
ORACLE_TEXT = "Hello World"
ORACLE_SIZE = 24
ORACLE_X, ORACLE_Y = 100, 700
# Helvetica average advance width ~0.5 em per char (rough estimate, kept from
# the 02-01 generator so its sidecars stay byte-identical).
AVG_CHAR_WIDTH = 0.5

# ─── Helvetica metrics ───────────────────────────────────────────────────────

# Adobe AFM advance widths (units per 1000 em) for the characters the Phase 2
# fixtures use.  The table fixture places its second cell at an exact 0.8 em
# gutter from the first cell's advance edge, so the widths must be exact — an
# estimate would move the gutter by several points.
HELVETICA_WIDTHS = {
    " ": 278, "!": 278, '"': 355, "#": 556, "$": 556, "%": 889, "&": 667,
    "'": 191, "(": 333, ")": 333, "*": 389, "+": 584, ",": 278, "-": 333,
    ".": 278, "/": 278,
    "0": 556, "1": 556, "2": 556, "3": 556, "4": 556, "5": 556, "6": 556,
    "7": 556, "8": 556, "9": 556,
    ":": 278, ";": 278, "<": 584, "=": 584, ">": 584, "?": 556, "@": 1015,
    "A": 667, "B": 667, "C": 722, "D": 722, "E": 667, "F": 611, "G": 778,
    "H": 722, "I": 278, "J": 500, "K": 667, "L": 556, "M": 833, "N": 722,
    "O": 778, "P": 667, "Q": 778, "R": 722, "S": 667, "T": 611, "U": 722,
    "V": 667, "W": 944, "X": 667, "Y": 667, "Z": 611,
    "[": 278, "\\": 278, "]": 278, "^": 469, "_": 556, "`": 333,
    "a": 556, "b": 556, "c": 500, "d": 556, "e": 556, "f": 278, "g": 556,
    "h": 556, "i": 222, "j": 222, "k": 500, "l": 222, "m": 833, "n": 556,
    "o": 556, "p": 556, "q": 556, "r": 333, "s": 500, "t": 278, "u": 556,
    "v": 500, "w": 722, "x": 500, "y": 500, "z": 500,
    "{": 334, "|": 260, "}": 334, "~": 584,
    # U+2022 BULLET — WinAnsi byte 0x95.
    "•": 350,
}


def text_width(text: str, size: float) -> float:
    """Advance width of [text] at [size] points, from the AFM table."""
    total = 0
    for ch in text:
        if ch not in HELVETICA_WIDTHS:
            raise ValueError(f"no Helvetica width for {ch!r}")
        total += HELVETICA_WIDTHS[ch]
    return total / 1000.0 * size


def pdf_literal(text: str) -> str:
    """Escape [text] as a PDF literal string (the font is WinAnsi-encoded)."""
    out = []
    for ch in text:
        if ch in "()\\":
            out.append("\\" + ch)
        elif ch == "•":
            out.append("\\225")  # WinAnsi bullet
        elif 32 <= ord(ch) <= 126:
            out.append(ch)
        else:
            raise ValueError(f"character {ch!r} is not representable in WinAnsi")
    return "".join(out)


# ─── PDF assembly ────────────────────────────────────────────────────────────


def assemble(objects: list[bytes]) -> bytes:
    """Wrap [objects] (1-based) into a PDF 1.4 file with an xref table."""
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n%s\nendobj\n" % (i, body)

    xref_pos = len(out)
    out += b"xref\n0 %d\n" % (len(objects) + 1)
    out += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        out += b"%010d 00000 n \n" % off
    out += (
        b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
        % (len(objects) + 1, xref_pos)
    )
    return bytes(out)


def stream_object(data: bytes) -> bytes:
    return b"<< /Length %d >>\nstream\n%s\nendstream" % (len(data), data)


def text_page_pdf(content: bytes) -> bytes:
    """A one-page PDF with a Helvetica (WinAnsi) font and [content]."""
    page_dict = (
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %d %d] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
        % (PAGE_W, PAGE_H)
    )
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        page_dict,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        stream_object(content),
    ]
    return assemble(objects)


def image_only_pdf() -> bytes:
    """A one-page PDF holding a single 8x8 grey image XObject and no text."""
    pixels = bytes((i * 4) % 256 for i in range(64))
    image = (
        b"<< /Type /XObject /Subtype /Image /Width 8 /Height 8 "
        b"/ColorSpace /DeviceGray /BitsPerComponent 8 /Length 64 >>\n"
        b"stream\n" + pixels + b"\nendstream"
    )
    content = b"q 200 0 0 100 100 600 cm /Im1 Do Q"
    page_dict = (
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %d %d] "
        b"/Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>"
        % (PAGE_W, PAGE_H)
    )
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        page_dict,
        image,
        stream_object(content),
    ]
    return assemble(objects)


# ─── Content-stream helpers ──────────────────────────────────────────────────


def show(x: float, y: float, size: float, text: str) -> str:
    """One absolute text-showing operation."""
    return f"BT /F1 {size:g} Tf 1 0 0 1 {x:.2f} {y:.2f} Tm ({pdf_literal(text)}) Tj ET"


def show_runs(size: float, runs: list[tuple[float, float, str]]) -> str:
    """One text object holding several absolute runs (several `Tj` runs)."""
    parts = [f"BT /F1 {size:g} Tf"]
    for x, y, text in runs:
        parts.append(f"1 0 0 1 {x:.2f} {y:.2f} Tm ({pdf_literal(text)}) Tj")
    parts.append("ET")
    return " ".join(parts)


# ─── Manifest helpers ────────────────────────────────────────────────────────


def line_expected(text: str, x: float, y: float, size: float, start_idx: int) -> dict:
    return {
        "text": text,
        "originPt": [x, y],
        "widthPt": round(text_width(text, size), 2),
        "heightPt": round(size, 2),
        "charRange": [start_idx, start_idx + len(text)],
    }


def oracle_line_expected(text: str, x: float, y: float, size: float, start_idx: int) -> dict:
    """The 02-01 sidecar format (average-width estimate), kept byte-identical."""
    return {
        "text": text,
        "originPt": [x, y],
        "widthPt": round(len(text) * AVG_CHAR_WIDTH * size, 2),
        "heightPt": round(size, 2),
        "charRange": [start_idx, start_idx + len(text)],
    }


def write_fixture(name: str, pdf: bytes, expected: list[dict]) -> None:
    os.makedirs(ASSETS, exist_ok=True)
    pdf_path = os.path.join(ASSETS, name)
    json_path = os.path.join(ASSETS, name + ".expected.json")
    with open(pdf_path, "wb") as f:
        f.write(pdf)
    with open(json_path, "w") as f:
        json.dump({"lines": expected}, f, indent=2)
    assert pdf.startswith(b"%PDF"), f"{name}: not a PDF"
    assert len(pdf) < 8192, f"{name}: too large ({len(pdf)} bytes)"
    print(f"wrote {pdf_path} ({len(pdf)} bytes)")
    print(f"wrote {json_path}")


# ─── The four coordinate-contract fixtures (byte-identical to plan 02-01) ────


def oracle_pdf(
    rotate: int = 0,
    crop_box: list | None = None,
    text_x: float = ORACLE_X,
    text_y: float = ORACLE_Y,
) -> bytes:
    """The 02-01 single-line PDF builder — its bytes must not change."""
    stream = (
        f"BT /F1 {ORACLE_SIZE} Tf {text_x:.2f} {text_y:.2f} Td "
        f"({pdf_literal(ORACLE_TEXT)}) Tj ET"
    ).encode()

    page_resources = b"<< /Font << /F1 4 0 R >> >>"
    page_dict_parts = [
        b"<< /Type /Page",
        b"/Parent 2 0 R",
        f"/MediaBox [0 0 {PAGE_W} {PAGE_H}]".encode(),
    ]
    if crop_box is not None:
        page_dict_parts.append(
            f"/CropBox [{' '.join(f'{v:g}' for v in crop_box)}]".encode()
        )
    if rotate != 0:
        page_dict_parts.append(f"/Rotate {rotate}".encode())
    page_dict_parts.append(f"/Resources {page_resources.decode()}".encode())
    page_dict_parts.append(b"/Contents 5 0 R")
    page_dict = b" ".join(page_dict_parts) + b" >>"

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        page_dict,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream),
    ]
    return assemble(objects)


# ─── The seven merge / block fixtures ────────────────────────────────────────

BODY_SIZE = 12.0
BODY_LEFT_X = 72.0
BODY_RIGHT_X = 330.0
BODY_BASELINES = [700.0, 680.0, 660.0, 640.0]


def two_columns_fixture() -> tuple[bytes, list[dict]]:
    """Two text columns on the same baselines.

    The horizontal gap between the columns is many em wide, so the D-12 gap
    rule must split every baseline into one line per column — never one line
    spanning both.
    """
    left_texts = [
        "Left column row one",
        "Left column row two",
        "Left column row three",
        "Left column row four",
    ]
    right_texts = [
        "Right column row one",
        "Right column row two",
        "Right column row three",
        "Right column row four",
    ]

    ops = []
    expected = []
    char_index = 0
    for baseline in BODY_BASELINES:
        left = left_texts[len(expected) // 2]
        right = right_texts[len(expected) // 2]
        ops.append(show(BODY_LEFT_X, baseline, BODY_SIZE, left))
        ops.append(show(BODY_RIGHT_X, baseline, BODY_SIZE, right))
        expected.append(line_expected(left, BODY_LEFT_X, baseline, BODY_SIZE, char_index))
        char_index += len(left)
        expected.append(line_expected(right, BODY_RIGHT_X, baseline, BODY_SIZE, char_index))
        char_index += len(right)

    return text_page_pdf("\n".join(ops).encode()), expected


def table_fixture() -> tuple[bytes, list[dict]]:
    """A three-row table whose cells are separated by a 0.8 em gutter.

    The gutter is under 2 em, so only the "gap of at least 1 em that exceeds
    2.5x the median word gap" rule of D-12 can split the cells — the word gaps
    inside the cells keep that median small.
    """
    rows = [
        ("First Name", "Ada Lovelace"),
        ("Second Name", "Alan Turing"),
        ("Third Name", "Grace Hopper"),
    ]
    gutter = 0.8 * BODY_SIZE  # 0.8 em

    ops = []
    expected = []
    char_index = 0
    for index, (left, right) in enumerate(rows):
        baseline = BODY_BASELINES[index]
        right_x = BODY_LEFT_X + text_width(left, BODY_SIZE) + gutter
        ops.append(show(BODY_LEFT_X, baseline, BODY_SIZE, left))
        ops.append(show(right_x, baseline, BODY_SIZE, right))
        expected.append(line_expected(left, BODY_LEFT_X, baseline, BODY_SIZE, char_index))
        char_index += len(left)
        expected.append(line_expected(right, right_x, baseline, BODY_SIZE, char_index))
        char_index += len(right)

    return text_page_pdf("\n".join(ops).encode()), expected


def list_bulleted_fixture() -> tuple[bytes, list[dict]]:
    """Three bulleted items — the bullet glyph starts a new block (D-16)."""
    items = [
        "• First bullet item",
        "• Second bullet item",
        "• Third bullet item",
    ]
    ops = []
    expected = []
    char_index = 0
    for index, item in enumerate(items):
        baseline = BODY_BASELINES[index]
        ops.append(show(BODY_LEFT_X, baseline, BODY_SIZE, item))
        expected.append(line_expected(item, BODY_LEFT_X, baseline, BODY_SIZE, char_index))
        char_index += len(item)

    return text_page_pdf("\n".join(ops).encode()), expected


def list_lettered_fixture() -> tuple[bytes, list[dict]]:
    """Three list items labelled `1.`, `a)` and `iii.` (D-16)."""
    items = [
        "1. Numbered item",
        "a) Lettered item",
        "iii. Roman item",
    ]
    ops = []
    expected = []
    char_index = 0
    for index, item in enumerate(items):
        baseline = BODY_BASELINES[index]
        ops.append(show(BODY_LEFT_X, baseline, BODY_SIZE, item))
        expected.append(line_expected(item, BODY_LEFT_X, baseline, BODY_SIZE, char_index))
        char_index += len(item)

    return text_page_pdf("\n".join(ops).encode()), expected


def justified_fixture() -> tuple[bytes, list[dict]]:
    """One logical line whose words are spread with EQUAL gaps.

    Every gap equals the median word gap, so neither D-12 rule can fire and
    the paragraph stays a single line.
    """
    words = [
        "This", "justified", "paragraph", "spreads", "its", "words", "evenly",
        "across", "the", "full", "width", "line",
    ]
    left = BODY_LEFT_X
    right = 540.0
    word_widths = [text_width(word, BODY_SIZE) for word in words]
    gap = (right - left - sum(word_widths)) / (len(words) - 1)
    assert 0.05 * BODY_SIZE < gap < 2 * BODY_SIZE, f"justification gap {gap} out of range"

    runs = []
    x = left
    for word, width in zip(words, word_widths):
        runs.append((x, BODY_BASELINES[0], word))
        x += width + gap

    expected = [
        line_expected(
            " ".join(words),
            left,
            BODY_BASELINES[0],
            BODY_SIZE,
            0,
        ),
    ]
    return text_page_pdf(show_runs(BODY_SIZE, runs).encode()), expected


def fragmented_runs_fixture() -> tuple[bytes, list[dict]]:
    """One logical line drawn as THREE separate `Tj` runs.

    The runs sit on one baseline with the same size, so they must merge into
    a single visual line (EDIT_TEXT-02).
    """
    size = BODY_SIZE
    baseline = BODY_BASELINES[0]
    run1 = "Frag"
    run2 = "mented"
    run3 = " runs"

    x1 = BODY_LEFT_X
    x2 = x1 + text_width(run1, size)
    x3 = x2 + text_width(run2, size)

    content = show_runs(size, [(x1, baseline, run1), (x2, baseline, run2), (x3, baseline, run3)])
    expected = [
        line_expected("Fragmented runs", x1, baseline, size, 0),
    ]
    return text_page_pdf(content.encode()), expected


def scan_only_fixture() -> tuple[bytes, list[dict]]:
    """One embedded image and no text at all — the `Empty` state (D-30)."""
    return image_only_pdf(), []


# ─── Entry point ─────────────────────────────────────────────────────────────


def main() -> None:
    os.makedirs(ASSETS, exist_ok=True)

    # The four coordinate-contract fixtures (plan 02-01) — byte-identical.
    oracle = [
        ("text_basic.pdf", 0, None),
        ("rotated90.pdf", 90, None),
        ("rotated270.pdf", 270, None),
        ("cropbox_offset.pdf", 0, [36, 36, 576, 756]),
    ]
    for name, rotate, crop_box in oracle:
        pdf = oracle_pdf(rotate=rotate, crop_box=crop_box)
        write_fixture(
            name,
            pdf,
            [oracle_line_expected(ORACLE_TEXT, ORACLE_X, ORACLE_Y, ORACLE_SIZE, 0)],
        )

    # The seven merge / block fixtures (plan 02-02).
    builders = [
        ("two_columns.pdf", two_columns_fixture),
        ("table.pdf", table_fixture),
        ("list_bulleted.pdf", list_bulleted_fixture),
        ("list_lettered.pdf", list_lettered_fixture),
        ("justified.pdf", justified_fixture),
        ("fragmented_runs.pdf", fragmented_runs_fixture),
        ("scan_only.pdf", scan_only_fixture),
    ]
    for name, builder in builders:
        pdf, expected = builder()
        write_fixture(name, pdf, expected)

    print(f"\nGenerated {len(oracle) + len(builders)} fixture PDFs + sidecars in {ASSETS}")


if __name__ == "__main__":
    main()
