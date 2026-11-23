#include "TextMutation.h"

#include <algorithm>
#include <string>
#include <vector>

namespace TextMutation {
namespace {

// An object belongs to the line when at least 60% of the OBJECT's own area is
// inside the line rectangle (a word glyph run, not a whole paragraph).
constexpr float kMinObjectAreaInsideLine = 0.6f;

// A visual word gap wider than 0.12 em is a space, even when the PDF stores no
// space character between the pieces.
constexpr float kWordGapFraction = 0.12f;

// Whitespace includes the PDF "invisible" characters: NBSP (U+00A0),
// zero-width space (U+200B) and soft hyphen (U+00AD). Treating them as
// whitespace lets a gap made from any of them match a normal space gap.
constexpr bool isWhitespace(char16_t c) {
    return c == u' ' || c == u'\t' || c == u'\r' || c == u'\n' ||
           c == 0x00A0 || c == 0x200B || c == 0x00AD;
}

// Collapse repeated whitespace inside a piece but KEEP a single leading and
// trailing space when the piece has one. Do not trim pieces: a space that
// belongs between two pieces must survive so lineText can see it at the join.
std::u16string normalizeWhitespace(const std::u16string& in) {
    std::u16string out;
    out.reserve(in.size());
    bool lastWasSpace = false;
    for (char16_t c : in) {
        const bool space = isWhitespace(c);
        if (space) {
            if (!lastWasSpace && !out.empty()) {
                out.push_back(u' ');
                lastWasSpace = true;
            }
        } else {
            out.push_back(c);
            lastWasSpace = false;
        }
    }
    return out;
}

// Comparison ignores whitespace entirely: PDFium may or may not emit a space
// character for the same visual word gap, and the editor infers one from the
// geometry. "KOFORIDUA TECHNICAL" and "KOFORIDUATECHNICAL" are the same line.
std::u16string compactForMatch(const std::u16string& in) {
    std::u16string out;
    out.reserve(in.size());
    for (char16_t c : in) {
        if (isWhitespace(c)) continue;
        out.push_back(c);
    }
    return out;
}

std::u16string textObjectText(FPDF_PAGEOBJECT object, FPDF_TEXTPAGE textPage) {
    if (!object || !textPage) return {};
    // With a null buffer this returns the required size in BYTES, including the
    // terminating NUL.
    const unsigned long needed = FPDFTextObj_GetText(object, textPage, nullptr, 0);
    if (needed <= sizeof(FPDF_WCHAR)) return {};

    std::vector<FPDF_WCHAR> buffer(needed / sizeof(FPDF_WCHAR) + 1);
    const unsigned long written =
        FPDFTextObj_GetText(object, textPage, buffer.data(), needed);
    if (written == 0) return {};

    std::u16string text(reinterpret_cast<const char16_t*>(buffer.data()),
                        written / sizeof(FPDF_WCHAR));
    while (!text.empty() && text.back() == 0) text.pop_back();
    return normalizeWhitespace(text);
}

// Fraction of the selected line rectangle covered by the object's bounds.
float overlapFraction(float objLeft, float objBottom, float objRight, float objTop,
                      float lineLeft, float lineBottom, float lineRight, float lineTop) {
    const float width = std::min(objRight, lineRight) - std::max(objLeft, lineLeft);
    const float height = std::min(objTop, lineTop) - std::max(objBottom, lineBottom);
    if (width <= 0.0f || height <= 0.0f) return 0.0f;
    const float lineArea = (lineRight - lineLeft) * (lineTop - lineBottom);
    if (lineArea <= 0.0f) return 0.0f;
    return (width * height) / lineArea;
}

// Fraction of the OBJECT's own area that lies inside the line rectangle.
float objectAreaInsideLine(float objLeft, float objBottom, float objRight, float objTop,
                           float lineLeft, float lineBottom, float lineRight, float lineTop) {
    const float width = std::min(objRight, lineRight) - std::max(objLeft, lineLeft);
    const float height = std::min(objTop, lineTop) - std::max(objBottom, lineBottom);
    const float area = (objRight - objLeft) * (objTop - objBottom);
    if (width <= 0.0f || height <= 0.0f || area <= 0.0f) return 0.0f;
    return (width * height) / area;
}

FPDF_WIDESTRING wideString(const std::u16string& text) {
    return reinterpret_cast<FPDF_WIDESTRING>(text.c_str());
}

// Collect the line's pieces from an already-loaded page and text page.
std::vector<LinePiece> collectPieces(FPDF_PAGE page, FPDF_TEXTPAGE textPage,
                                     float left, float bottom, float right, float top) {
    std::vector<LinePiece> pieces;
    if (!page || !textPage) return pieces;

    const int count = FPDFPage_CountObjects(page);
    for (int i = 0; i < count; ++i) {
        FPDF_PAGEOBJECT object = FPDFPage_GetObject(page, i);
        if (!object || FPDFPageObj_GetType(object) != FPDF_PAGEOBJ_TEXT) continue;

        float objectLeft = 0.0f;
        float objectBottom = 0.0f;
        float objectRight = 0.0f;
        float objectTop = 0.0f;
        if (FPDFPageObj_GetBounds(object, &objectLeft, &objectBottom,
                                  &objectRight, &objectTop) == 0) {
            continue;
        }
        if (objectAreaInsideLine(objectLeft, objectBottom, objectRight, objectTop,
                                 left, bottom, right, top) < kMinObjectAreaInsideLine) {
            continue;
        }

        float fontSize = 0.0f;
        FPDFTextObj_GetFontSize(object, &fontSize);

        pieces.push_back({i, objectLeft, objectBottom, objectRight, objectTop, fontSize,
                          textObjectText(object, textPage)});
    }

    std::stable_sort(pieces.begin(), pieces.end(),
                     [](const LinePiece& a, const LinePiece& b) { return a.left < b.left; });
    return pieces;
}

// Recreate |object| as a new text object with |font| (or its own font when the
// handle is null). Returns the new object, or null when nothing was changed.
FPDF_PAGEOBJECT replaceObjectPointer(FPDF_PAGE page, EditingDocument* doc,
                                     FPDF_PAGEOBJECT object,
                                     const std::u16string& newText,
                                     FPDF_FONT font, float fontSizePt,
                                     int color, const float* matrix) {
    if (!page || !doc || !doc->docPtr() || !object) return nullptr;

    FPDF_FONT useFont = font ? font : FPDFTextObj_GetFont(object);
    if (!useFont) return nullptr;

    float size = 0.0f;
    FPDFTextObj_GetFontSize(object, &size);
    if (fontSizePt > 0.0f) size = fontSizePt;
    if (size <= 0.0f) size = 10.0f;

    FPDF_PAGEOBJECT replacement =
        FPDFPageObj_CreateTextObj(doc->docPtr(), useFont, size);
    if (!replacement) return nullptr;

    if (FPDFText_SetText(replacement, wideString(newText)) == 0) {
        FPDFPageObj_Destroy(replacement);
        return nullptr;
    }

    // Colour: an explicit override (alpha != 0) wins; otherwise reuse the
    // original object's fill colour, which is what the page actually shows.
    const unsigned int overrideAlpha = (static_cast<unsigned int>(color) >> 24) & 0xFF;
    if (overrideAlpha != 0) {
        FPDFPageObj_SetFillColor(replacement,
                                 (static_cast<unsigned int>(color) >> 16) & 0xFF,
                                 (static_cast<unsigned int>(color) >> 8) & 0xFF,
                                 static_cast<unsigned int>(color) & 0xFF,
                                 overrideAlpha);
    } else {
        unsigned int r = 0;
        unsigned int g = 0;
        unsigned int b = 0;
        unsigned int a = 255;
        if (FPDFPageObj_GetFillColor(object, &r, &g, &b, &a)) {
            FPDFPageObj_SetFillColor(replacement, r, g, b, a);
        }
    }

    // Position: an explicit matrix wins; otherwise the old object's matrix
    // carries its exact origin, rotation and scale. Using the line rectangle's
    // bottom edge instead put the new text on the wrong baseline.
    if (matrix) {
        FS_MATRIX overrideMatrix = {
            matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5],
        };
        FPDFPageObj_SetMatrix(replacement, &overrideMatrix);
    } else {
        FS_MATRIX oldMatrix = {1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};
        if (FPDFPageObj_GetMatrix(object, &oldMatrix)) {
            FPDFPageObj_SetMatrix(replacement, &oldMatrix);
        }
    }

    // Insert the replacement BEFORE removing the original. InsertObject returns
    // void and takes ownership on success; RemoveObject reports success.
    FPDFPage_InsertObject(page, replacement);
    if (FPDFPage_RemoveObject(page, object) == 0) {
        if (FPDFPage_RemoveObject(page, replacement)) {
            FPDFPageObj_Destroy(replacement);
        }
        return nullptr;
    }
    FPDFPageObj_Destroy(object);
    return replacement;
}

} // namespace

bool setText(EditingDocument* doc, int pageIndex, int objectIndex,
             const std::u16string& newText) {
    if (!doc || !doc->docPtr()) return false;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return false;

    FPDF_PAGEOBJECT object = FPDFPage_GetObject(page, objectIndex);
    if (!object || FPDFPageObj_GetType(object) != FPDF_PAGEOBJ_TEXT) {
        doc->closePage(page);
        return false;
    }

    const bool ok = FPDFText_SetText(object, wideString(newText)) != 0;
    if (ok) {
        FPDFPage_GenerateContent(page);
    }
    doc->closePage(page);
    return ok;
}

bool replaceTextObject(EditingDocument* doc, int pageIndex, int objectIndex,
                       const std::u16string& newText,
                       FPDF_FONT font, float fontSizePt,
                       int color, const float* matrix) {
    if (!doc || !doc->docPtr()) return false;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return false;

    FPDF_PAGEOBJECT oldObject = FPDFPage_GetObject(page, objectIndex);
    if (!oldObject || FPDFPageObj_GetType(oldObject) != FPDF_PAGEOBJ_TEXT) {
        doc->closePage(page);
        return false;
    }

    const bool ok = replaceObjectPointer(page, doc, oldObject, newText, font,
                                         fontSizePt, color, matrix) != nullptr;
    const bool generated = ok && FPDFPage_GenerateContent(page) != 0;
    doc->closePage(page);
    return generated;
}

int getTextObjectCount(EditingDocument* doc, int pageIndex) {
    if (!doc) return 0;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return 0;
    const int count = FPDFPage_CountObjects(page);
    doc->closePage(page);
    return count;
}

int getTextObjectCharCount(EditingDocument* doc, int pageIndex, int objectIndex) {
    if (!doc) return 0;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return 0;

    int result = 0;
    FPDF_PAGEOBJECT object = FPDFPage_GetObject(page, objectIndex);
    if (object && FPDFPageObj_GetType(object) == FPDF_PAGEOBJ_TEXT) {
        FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
        if (textPage) {
            result = static_cast<int>(textObjectText(object, textPage).size());
            FPDFText_ClosePage(textPage);
        }
    }
    doc->closePage(page);
    return result;
}

int findTextObject(EditingDocument* doc, int pageIndex,
                   float left, float bottom, float right, float top,
                   const std::u16string& text) {
    if (!doc) return -1;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return -1;

    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (!textPage) {
        doc->closePage(page);
        return -1;
    }

    const std::u16string wanted = compactForMatch(text);
    const int count = FPDFPage_CountObjects(page);
    int found = -1;
    int matches = 0;

    for (int i = 0; i < count; ++i) {
        FPDF_PAGEOBJECT object = FPDFPage_GetObject(page, i);
        if (!object || FPDFPageObj_GetType(object) != FPDF_PAGEOBJ_TEXT) continue;

        float objectLeft = 0.0f;
        float objectBottom = 0.0f;
        float objectRight = 0.0f;
        float objectTop = 0.0f;
        if (FPDFPageObj_GetBounds(object, &objectLeft, &objectBottom,
                                  &objectRight, &objectTop) == 0) {
            continue;
        }
        if (overlapFraction(objectLeft, objectBottom, objectRight, objectTop,
                            left, bottom, right, top) < 0.5f) {
            continue;
        }
        if (compactForMatch(textObjectText(object, textPage)) != wanted) continue;

        found = i;
        ++matches;
    }

    FPDFText_ClosePage(textPage);
    doc->closePage(page);
    return matches == 1 ? found : -1;
}

std::vector<LinePiece> linePieces(EditingDocument* doc, int pageIndex,
                                  float left, float bottom, float right, float top) {
    if (!doc) return {};
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return {};

    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (!textPage) {
        doc->closePage(page);
        return {};
    }

    std::vector<LinePiece> pieces =
        collectPieces(page, textPage, left, bottom, right, top);
    FPDFText_ClosePage(textPage);
    doc->closePage(page);
    return pieces;
}

std::u16string lineText(const std::vector<LinePiece>& pieces) {
    std::u16string out;
    const LinePiece* previous = nullptr;
    for (const LinePiece& piece : pieces) {
        if (previous != nullptr) {
            const bool previousEndsSpace =
                !previous->text.empty() && isWhitespace(previous->text.back());
            const bool nextStartsSpace =
                !piece.text.empty() && isWhitespace(piece.text.front());
            const float size = std::max(previous->fontSize, piece.fontSize);
            const float gap = piece.left - previous->right;
            // Single-character pieces use a wider threshold so a gap inside a
            // word (one char per object) does not get a space inserted.
            const float threshold =
                (previous->text.size() == 1 && piece.text.size() == 1)
                    ? 0.25f * size
                    : kWordGapFraction * size;
            if (!previousEndsSpace && !nextStartsSpace && size > 0.0f &&
                gap > threshold) {
                out.push_back(u' ');
            }
        }
        out += piece.text;
        previous = &piece;
    }
    return normalizeWhitespace(out);
}

float lineFontSize(EditingDocument* doc, int pageIndex,
                   float left, float bottom, float right, float top) {
    if (!doc) return 0.0f;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return 0.0f;

    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (!textPage) {
        doc->closePage(page);
        return 0.0f;
    }

    const std::vector<LinePiece> pieces =
        collectPieces(page, textPage, left, bottom, right, top);
    FPDFText_ClosePage(textPage);
    doc->closePage(page);

    for (const LinePiece& piece : pieces) {
        if (piece.fontSize > 0.0f) return piece.fontSize;
    }
    return 0.0f;
}

int editLine(EditingDocument* doc, int pageIndex,
             float left, float bottom, float right, float top,
             const std::u16string& expectedLineText,
             const std::u16string& newText,
             FPDF_FONT font, float fontSizePt,
             int color, const float* matrix, bool forceReplace) {
    if (!doc || !doc->docPtr()) return LINE_EDIT_NO_OBJECTS;
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return LINE_EDIT_NO_OBJECTS;

    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (!textPage) {
        doc->closePage(page);
        return LINE_EDIT_NO_OBJECTS;
    }

    std::vector<LinePiece> pieces =
        collectPieces(page, textPage, left, bottom, right, top);

    std::u16string joined;
    for (const LinePiece& piece : pieces) joined += piece.text;

    const bool piecesAddUp =
        !pieces.empty() &&
        compactForMatch(joined) == compactForMatch(expectedLineText);

    // Hold the pointers before any mutation: removing objects shifts indices.
    std::vector<FPDF_PAGEOBJECT> others;
    FPDF_PAGEOBJECT first = nullptr;
    if (piecesAddUp) {
        first = FPDFPage_GetObject(page, pieces[0].index);
        for (size_t i = 1; i < pieces.size(); ++i) {
            FPDF_PAGEOBJECT other = FPDFPage_GetObject(page, pieces[i].index);
            if (other && other != first) others.push_back(other);
        }
    }

    FPDFText_ClosePage(textPage);

    if (!piecesAddUp) {
        const int result = pieces.empty() ? LINE_EDIT_NO_OBJECTS : LINE_EDIT_TEXT_MISMATCH;
        doc->closePage(page);
        return result;
    }
    if (!first) {
        doc->closePage(page);
        return LINE_EDIT_NO_OBJECTS;
    }

    // Replace the first piece's text; fall back to a new object (bundled font)
    // when the original font cannot encode the new text.
    bool mutated = false;
    if (!forceReplace) {
        mutated = FPDFText_SetText(first, wideString(newText)) != 0;
    }
    if (!mutated) {
        mutated = replaceObjectPointer(page, doc, first, newText, font, fontSizePt,
                                       color, matrix) != nullptr;
    }
    if (!mutated) {
        doc->closePage(page);
        return LINE_EDIT_SET_TEXT_FAILED;
    }

    // Remove the rest of the line, each object exactly once.
    for (FPDF_PAGEOBJECT other : others) {
        if (other && FPDFPage_RemoveObject(page, other)) {
            FPDFPageObj_Destroy(other);
        }
    }

    const bool generated = FPDFPage_GenerateContent(page) != 0;
    doc->closePage(page);
    return generated ? LINE_EDIT_OK : LINE_EDIT_GENERATE_FAILED;
}

} // namespace TextMutation
