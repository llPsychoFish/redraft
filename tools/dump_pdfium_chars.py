#!/usr/bin/env python3
"""Dump PDFium's own character boxes for a PDF as Kotlin `RealChar(...)` source.

The Phase 2 reducer is tested on the JVM against character boxes that PDFium
itself produced (`RealPdfiumFixture`), because synthetic ink boxes hide the
bugs: real boxes put a quote or an '=' above the baseline and hang a 'g' below
it.  This script is how that fixture data is captured, so a new page can be
added without a device or an emulator.

It mirrors `TextExtraction.pageTextLayout` exactly:
  * one text page per page,
  * `FPDFText_GetCharBox` normalised with per-axis min/max,
  * the font size from the char's text object,
  * the character taken from the page's full string, so PDFium's own CR/LF
    line-break chars are included just as the app sees them.

PDFium is the same engine the app links, so the geometry is the app's geometry.

Usage:
    python3 tools/dump_pdfium_chars.py <file.pdf> [--page N] [--out <kotlin file>]

Requires: pip install pypdfium2
"""

import argparse
import sys

import pypdfium2 as pdfium


def dump(path: str, page_index: int) -> list[dict]:
    doc = pdfium.PdfDocument(path)
    page = doc[page_index]
    textpage = page.get_textpage()
    count = textpage.count_chars()
    full = textpage.get_text_range(0, count) or ""

    chars = []
    for i in range(count):
        left, bottom, right, top = textpage.get_charbox(i)
        obj = textpage.get_textobj(i)
        size = obj.get_font_size() if obj is not None else 0.0
        chars.append(
            {
                "index": i,
                "text": full[i] if i < len(full) else "",
                "left": float(left),
                "bottom": float(bottom),
                "right": float(right),
                "top": float(top),
                "size": float(size),
            }
        )
    doc.close()
    return chars


def as_kotlin(chars: list[dict]) -> str:
    lines = []
    for c in chars:
        text = (
            c["text"]
            .replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        )
        lines.append(
            '    RealChar({index}, "{text}", {left}f, {bottom}f, {right}f, {top}f, {size}f),'.format(
                index=c["index"],
                text=text,
                left=repr(c["left"]),
                bottom=repr(c["bottom"]),
                right=repr(c["right"]),
                top=repr(c["top"]),
                size=repr(c["size"]),
            )
        )
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("pdf")
    ap.add_argument("--page", type=int, default=0)
    ap.add_argument("--out", help="write Kotlin source here instead of stdout")
    args = ap.parse_args()

    chars = dump(args.pdf, args.page)
    body = as_kotlin(chars)
    header = (
        f"// PDFium {pdfium.PDFIUM_INFO} — {args.pdf} page {args.page} — "
        f"{len(chars)} chars\n"
    )
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(header + body + "\n")
        print(f"wrote {len(chars)} chars to {args.out}", file=sys.stderr)
    else:
        print(header + body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
