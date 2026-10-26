#!/usr/bin/env python3
"""Deterministically generate test fixture PDFs for Phase 2 (Selection & Hit-Testing).

Generates four fixture PDFs into app/src/androidTest/assets/ plus a sidecar
.expected.json for each recording per-line {text, originPt, widthPt, heightPt,
charRange} computed from the generator's own text placement.

Run from the repo root: python3 tools/gen_sample_pdf.py

Fixtures:
  - text_basic.pdf       — US Letter, one line at (100, 700), size 24, no rotation/crop
  - rotated90.pdf        — US Letter, /Rotate 90, same text placement
  - rotated270.pdf       — US Letter, /Rotate 270, same text placement
  - cropbox_offset.pdf   — US Letter, /CropBox [36 36 576 756], text at absolute (100, 700)
"""

import json
import os

ASSETS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "androidTest", "assets",
)

PAGE_W, PAGE_H = 612, 792  # US Letter in points
FONT_SIZE = 24
TEXT = "Hello World"
TEXT_X, TEXT_Y = 100, 700  # baseline start, absolute PDF user space

# Helvetica average advance width ~0.5 em per char (rough estimate for expected.json)
AVG_CHAR_WIDTH = 0.5


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


def build_pdf(
    rotate: int = 0,
    crop_box: list[float] | None = None,
    text_x: float = TEXT_X,
    text_y: float = TEXT_Y,
) -> bytes:
    """Build a single-page PDF 1.4 with optional /Rotate and /CropBox.

    Text is always placed at (text_x, text_y) in ABSOLUTE PDF user space,
    regardless of /Rotate or /CropBox — this is what makes the fixtures
    deterministic for the coordinate-transform oracle test.
    """
    stream = text_stream(TEXT, text_x, text_y, FONT_SIZE)

    # Page dict: MediaBox is always the full page; /Rotate and /CropBox are optional.
    page_resources = b"<< /Font << /F1 4 0 R >> >>"
    page_dict_parts = [
        b"<< /Type /Page",
        b"/Parent 2 0 R",
        f"/MediaBox [0 0 {PAGE_W} {PAGE_H}]".encode(),
    ]
    if crop_box is not None:
        page_dict_parts.append(f"/CropBox [{' '.join(f'{v:g}' for v in crop_box)}]".encode())
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


def write_fixture(name: str, pdf: bytes, expected: list[dict]) -> None:
    os.makedirs(ASSETS, exist_ok=True)
    pdf_path = os.path.join(ASSETS, name)
    json_path = os.path.join(ASSETS, name + ".expected.json")
    with open(pdf_path, "wb") as f:
        f.write(pdf)
    with open(json_path, "w") as f:
        json.dump({"lines": expected}, f, indent=2)
    print(f"wrote {pdf_path} ({len(pdf)} bytes)")
    print(f"wrote {json_path}")


def main() -> None:
    fixtures = [
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
    ]

    for name, rotate, crop_box, expected in fixtures:
        pdf = build_pdf(rotate=rotate, crop_box=crop_box)
        write_fixture(name, pdf, expected)
        assert pdf.startswith(b"%PDF"), f"{name}: not a PDF"
        assert len(pdf) < 8192, f"{name}: suspiciously large ({len(pdf)} bytes)"

    # Also regenerate the main sample.pdf (unchanged content, new deterministic path)
    sample = build_pdf(rotate=0, crop_box=None)
    sample_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "sample.pdf",
    )
    os.makedirs(os.path.dirname(sample_path), exist_ok=True)
    with open(sample_path, "wb") as f:
        f.write(sample)
    print(f"wrote {sample_path} ({len(sample)} bytes)")


if __name__ == "__main__":
    main()
