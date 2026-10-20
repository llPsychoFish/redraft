#!/usr/bin/env python3
"""Deterministically generate app/src/main/assets/sample.pdf.

A minimal, valid, single-page PDF 1.4 (US Letter) with one line of text.
Run from the repo root: python3 tools/gen_sample_pdf.py
"""

import os

OUTPUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "sample.pdf",
)

STREAM = b"BT /F1 24 Tf 72 720 Td (Redraft sample) Tj ET"


def build_pdf() -> bytes:
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
        ),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length %d >>\nstream\n%s\nendstream" % (len(STREAM), STREAM),
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


def main() -> None:
    pdf = build_pdf()
    assert pdf.startswith(b"%PDF"), "not a PDF"
    assert len(pdf) > 300, "suspiciously small"
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "wb") as f:
        f.write(pdf)
    print(f"wrote {OUTPUT} ({len(pdf)} bytes)")


if __name__ == "__main__":
    main()
