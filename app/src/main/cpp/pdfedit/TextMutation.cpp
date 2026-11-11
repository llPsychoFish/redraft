#include "TextMutation.h"

#include <algorithm>
#include <vector>

namespace TextMutation {
namespace {

// A line's char boxes and its text object's bounds are nearly identical; a
// match that covers less than half of the selected line is a different object.
constexpr float kMinOverlapFraction = 0.5f;

std::u16string normalizeWhitespace(const std::u16string& in) {
    std::u16string out;
    out.reserve(in.size());
    bool lastWasSpace = true;
    for (char16_t c : in) {
        const bool space = c == u' ' || c == u'\t' || c == u'\r' || c == u'\n';
        if (space) {
            if (!lastWasSpace) out.push_back(u' ');
            lastWasSpace = true;
        } else {
            out.push_back(c);
            lastWasSpace = false;
        }
    }
    while (!out.empty() && out.back() == u' ') out.pop_back();
    return out;
}

// Comparison ignores whitespace entirely: PDFium may or may not emit a space
// character for the same visual word gap, and the editor infers one from the
// geometry. "KOFORIDUA TECHNICAL" and "KOFORIDUATECHNICAL" are the same line.
std::u16string compactForMatch(const std::u16string& in) {
    std::u16string out;
    out.reserve(in.size());
    for (char16_t c : in) {
        if (c == u' ' || c == u'\t' || c == u'\r' || c == u'\n') continue;
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

FPDF_WIDESTRING wideString(const std::u16string& text) {
    return reinterpret_cast<FPDF_WIDESTRING>(text.c_str());
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

    // Reuse the original font when the caller has no fallback: the font handle
    // is owned by this document and stays valid until it is closed.
    FPDF_FONT useFont = font ? font : FPDFTextObj_GetFont(oldObject);
    if (!useFont) {
        doc->closePage(page);
        return false;
    }

    float size = 0.0f;
    FPDFTextObj_GetFontSize(oldObject, &size);
    if (fontSizePt > 0.0f) size = fontSizePt;
    if (size <= 0.0f) size = 10.0f;

    FPDF_PAGEOBJECT newObject = FPDFPageObj_CreateTextObj(doc->docPtr(), useFont, size);
    if (!newObject) {
        doc->closePage(page);
        return false;
    }

    if (FPDFText_SetText(newObject, wideString(newText)) == 0) {
        FPDFPageObj_Destroy(newObject);
        doc->closePage(page);
        return false;
    }

    // Preserve the original fill colour when PDFium can report it; fall back to
    // the ARGB value the UI read from the extracted fragment.
    unsigned int r = (color >> 16) & 0xFF;
    unsigned int g = (color >> 8) & 0xFF;
    unsigned int b = color & 0xFF;
    unsigned int a = (color >> 24) & 0xFF;
    FPDFPageObj_GetFillColor(oldObject, &r, &g, &b, &a);
    FPDFPageObj_SetFillColor(newObject, r, g, b, a);

    // The old object's matrix carries its exact position, rotation and scale.
    FS_MATRIX oldMatrix = {1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};
    if (FPDFPageObj_GetMatrix(oldObject, &oldMatrix)) {
        FPDFPageObj_SetMatrix(newObject, &oldMatrix);
    } else if (matrix) {
        FS_MATRIX fallback = {matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]};
        FPDFPageObj_SetMatrix(newObject, &fallback);
    }

    // Insert the replacement BEFORE removing the original. InsertObject returns
    // void and takes ownership on success; RemoveObject reports success.
    FPDFPage_InsertObject(page, newObject);
    if (FPDFPage_RemoveObject(page, oldObject) == 0) {
        // Undo the insertion so the page keeps exactly one copy of the line.
        if (FPDFPage_RemoveObject(page, newObject)) {
            FPDFPageObj_Destroy(newObject);
        }
        doc->closePage(page);
        return false;
    }
    FPDFPageObj_Destroy(oldObject);

    const bool ok = FPDFPage_GenerateContent(page) != 0;
    doc->closePage(page);
    return ok;
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
                            left, bottom, right, top) < kMinOverlapFraction) {
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

} // namespace TextMutation
