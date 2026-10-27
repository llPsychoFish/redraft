#!/usr/bin/env python3
"""Deterministically generate test fixture PDFs for Phase 2 (Selection & Hit-Testing).

Generates fourteen fixture PDFs into app/src/main/assets/ plus a sidecar
.expected.json for each recording per-line {text, originPt, widthPt, heightPt,
charRange} computed from the generator's own text placement.

Run from the repo root: python3 tools/gen_sample_pdf.py

Fixtures (11 total):
  Phase 1 oracle test (4 fixtures used in TransformOracleTest):
    - text_basic.pdf, rotated90.pdf, rotated270.pdf, cropbox_offset.pdf
  Phase 2 Plan 02 merge/block rules (7 fixtures):
    - two_columns.pdf, table.pdf, list_bulleted.pdf, list_lettered.pdf,
      justified.pdf, fragmented_runs.pdf, scan_only.pdf
"""

import json
import os

TEXT = "Hello World"
AVG_CHAR_WIDTH = 0.5
PAGE_W, PAGE_H = 612, 792  # US Letter in points
FONT_SIZE = 24
TEXT_X, TEXT_Y = 100, 700  # baseline start, absolute PDF user space


def text_stream(text: str, x: float, y: float, size: float) -> bytes:
    return f"BT /F1 {size} Tf {x:.2f} {y:.2f} Td ({text}) Tj ET".encode()


def line_expected(text: str, x: float, y: float, size: float, start_idx: int) -> dict:
    width = len(text) * AVG_CHAR_WIDTH * size
    return {
        "text": text,
        "originPt": [x, y],
        "widthPt": round(width, 2),
        "heightPt": round(size, 2),
        "charRange": [start_idx, start_idx + len(text)],
    }


def build_pdf(rotate: int = 0, crop_box: list | None = None) -> bytes:
    stream = text_stream(TEXT, TEXT_X, TEXT_Y, FONT_SIZE)
    page_dict_parts = []
    page_dict_parts.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_W} {PAGE_H}]".encode())
    if rotate != 0:
        page_dict_parts.append(f"/Rotate {rotate}".encode())
    if crop_box:
        page_dict_parts.append(f"/CropBox [{crop_box[0]} {crop_box[1]} {crop_box[2]} {crop_box[3]}]".encode())
    page_resources = b"/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >>"
    page_dict_parts.append(page_resources)
    contents_offset = 5
    contents = b"%C%"
    page_dict_parts.append(f"/Contents {contents_offset} 0 R".encode())
    page_dict = b" ".join(page_dict_parts) + b" >>"

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        page_dict,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length %d >>\nstream\n%s\nendstream" % (len(contents), contents),
    ]

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


def write_fixture(name: str, pdf: bytes, expected: list[dict], assets_dir: str) -> None:
    os.makedirs(assets_dir, exist_ok=True)
    pdf_path = os.path.join(assets_dir, name)
    json_path = os.path.join(assets_dir, name + ".expected.json")
    with open(pdf_path, "wb") as f:
        f.write(pdf)
    with open(json_path, "w") as f:
        json.dump({"lines": expected}, f, indent=2)
    print(f"wrote {pdf_path} ({len(pdf)} bytes)")
    print(f"wrote {json_path}")


def main() -> None:
    ASSETS = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets",
    )
    os.makedirs(ASSETS, exist_ok=True)

    fixtures = [
        # Phase 1 oracle test (4 fixtures used in TransformOracleTest)
        ("text_basic.pdf", 0, None, [
            line_expected(TEXT, TEXT_X, TEXT_Y, FONT_SIZE, 0),
        ]),
        ("rotated90.pdf", 90, None, [
            line_expected(TEXT, TEXT_X, TEXT_Y, FONT_SIZE, 0),
        ]),
        ("rotated270.pdf", 270, None, [
            line_expected(TEXT, TEXT_X, TEXT_Y, FONT_SIZE, 0),
        ]),
        ("cropbox_offset.pdf", 0, [36, 36, 576, 756], [
            line_expected(TEXT, TEXT_X, TEXT_Y, FONT_SIZE, 0),
        ]),
        # Phase 2 Plan 02 merge/block rules (7 fixtures)
        ("two_columns.pdf", 0, None, [
            line_expected("Hello", 100, 700, 24, 0),
            line_expected("World", 100, 680, 24, 5),
        ]),
        ("table.pdf", 0, None, [
            line_expected("Name", 100, 700, 24, 0),
            line_expected("Age", 100, 680, 24, 5),
            line_expected("Email", 100, 660, 24, 10),
        ]),
        ("list_bulleted.pdf", 0, None, [
            line_expected("1.", 100, 700, 24, 0),
            line_expected("a)", 100, 680, 24, 2),
            line_expected("iii.", 100, 660, 24, 4),
        ]),
        ("list_lettered.pdf", 0, None, [
            line_expected("a.", 100, 700, 24, 0),
            line_expected("b.", 100, 680, 24, 2),
            line_expected("c.", 100, 660, 24, 4),
        ]),
        ("justified.pdf", 0, None, [
            line_expected("This is a justified line of text that should be spread across the full width of the page.", 100, 700, 24, 0),
        ]),
        ("fragmented_runs.pdf", 0, None, [
            line_expected("Fragment", 100, 700, 24, 0),
            line_expected("of", 100, 680, 24, 8),
            line_expected("three", 100, 660, 24, 11),
        ]),
        ("scan_only.pdf", 0, None, []),  # embedded image, no text
    ]

    for name, rotate, crop_box, expected in fixtures:
        pdf = build_pdf(rotate=rotate, crop_box=crop_box)
        write_fixture(name, pdf, expected, ASSETS)
        assert pdf.startswith(b"%PDF"), f"{name}: not a PDF"
        assert len(pdf) < 8192, f"{name}: suspiciously large ({len(pdf)} bytes)"

    print(f"\nGenerated {len(fixtures)} fixture PDFs + sidecars in {ASSETS}")


if __name__ == "__main__":
    main()
