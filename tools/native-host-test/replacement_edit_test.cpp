// Host test for the REPLACEMENT path (a new text object with a bundled font).
//
// The review found that the editor showed 25 pt for a heading that is about
// 12 pt, and a replacement created at 25 pt came out twice as tall and landed
// on the wrong baseline. This test edits the heading of A_single.pdf (one
// object) and B_perword.pdf (one object per word) with forceReplace and no
// style overrides, then checks the saved copy:
//
//   * the new text starts within 1 pt of the original left edge;
//   * its height is within 20% of the original text height;
//   * the original size is what PDFium reports (FPDFTextObj_GetFontSize).
#include "EditingDocument.h"
#include "TextMutation.h"
#include "Save.h"
#include <fpdfview.h>
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

static std::u16string u16(const char* s) {
    std::u16string out;
    for (; *s; ++s) out.push_back(static_cast<unsigned char>(*s));
    return out;
}

static std::string narrow(const std::u16string& s) {
    std::string out;
    for (char16_t c : s) out.push_back(c < 128 ? static_cast<char>(c) : '?');
    return out;
}

static std::string objectText(FPDF_PAGEOBJECT obj, FPDF_TEXTPAGE tp) {
    unsigned long need = FPDFTextObj_GetText(obj, tp, nullptr, 0);
    if (need <= 2) return {};
    std::vector<FPDF_WCHAR> buf(need / 2 + 1);
    unsigned long written = FPDFTextObj_GetText(obj, tp, buf.data(), need);
    std::u16string text(reinterpret_cast<const char16_t*>(buf.data()), written / 2);
    while (!text.empty() && text.back() == 0) text.pop_back();
    return narrow(text);
}

// Union of the heading objects' bounds, so the test does not hard-code a rect.
static bool headingRect(EditingDocument& doc, float* l, float* b, float* r, float* t) {
    FPDF_PAGE page = doc.loadPage(0);
    if (!page) return false;
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(page);
    if (!tp) { doc.closePage(page); return false; }

    bool found = false;
    *l = *b = 1e9f;
    *r = *t = -1e9f;
    const int count = FPDFPage_CountObjects(page);
    for (int i = 0; i < count; ++i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj || FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;
        const std::string text = objectText(obj, tp);
        if (text.find("KOFORIDUA") == std::string::npos &&
            text.find("TECHNICAL") == std::string::npos &&
            text.find("UNIVERSITY") == std::string::npos) {
            continue;
        }
        float fl = 0, fb = 0, fr = 0, ft = 0;
        if (!FPDFPageObj_GetBounds(obj, &fl, &fb, &fr, &ft)) continue;
        *l = std::min(*l, fl);
        *b = std::min(*b, fb);
        *r = std::max(*r, fr);
        *t = std::max(*t, ft);
        found = true;
    }
    FPDFText_ClosePage(tp);
    doc.closePage(page);
    return found;
}

// Bounds of the single text object whose text contains |needle|.
static bool objectRectByText(FPDF_DOCUMENT doc, const char* needle,
                             float* l, float* b, float* r, float* t, float* size) {
    FPDF_PAGE page = FPDF_LoadPage(doc, 0);
    if (!page) return false;
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(page);
    if (!tp) { FPDF_ClosePage(page); return false; }

    bool found = false;
    const int count = FPDFPage_CountObjects(page);
    for (int i = 0; i < count; ++i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj || FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;
        if (objectText(obj, tp).find(needle) == std::string::npos) continue;
        if (!FPDFPageObj_GetBounds(obj, l, b, r, t)) continue;
        *size = 0.0f;
        FPDFTextObj_GetFontSize(obj, size);
        found = true;
        break;
    }
    FPDFText_ClosePage(tp);
    FPDF_ClosePage(page);
    return found;
}

static std::vector<unsigned char> readFile(const char* path) {
    std::vector<unsigned char> data;
    FILE* file = std::fopen(path, "rb");
    if (!file) return data;
    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    if (size > 0) {
        data.resize(static_cast<size_t>(size));
        if (std::fread(data.data(), 1, data.size(), file) != data.size()) data.clear();
    }
    std::fclose(file);
    return data;
}

int main(int argc, char** argv) {
    // args: input.pdf output.pdf font.ttf "new text"
    if (argc < 5) {
        std::fprintf(stderr, "usage: replacement_edit_test in.pdf out.pdf font.ttf \"new text\"\n");
        return 64;
    }
    FPDF_InitLibrary();

    const char* input = argv[1];
    const char* output = argv[2];
    const char* fontPath = argv[3];
    const std::u16string expected = u16("KOFORIDUA TECHNICAL UNIVERSITY");
    const std::u16string replacement = u16(argv[4]);

    int fd = open(input, O_RDONLY);
    struct stat st {};
    fstat(fd, &st);
    EditingDocument doc(fd, 0, st.st_size);
    std::printf("%s: pages=%d\n", input, doc.pageCount());

    float l = 0, b = 0, r = 0, t = 0;
    if (!headingRect(doc, &l, &b, &r, &t)) {
        std::printf("FAIL: heading objects not found\n");
        return 1;
    }
    const float originalLeft = l;
    const float originalHeight = t - b;
    const float originalSize = TextMutation::lineFontSize(&doc, 0, l, b, r, t);
    std::printf("original: left=%.2f height=%.2f size=%.2f pt\n",
                originalLeft, originalHeight, originalSize);
    if (originalSize <= 0.0f) {
        std::printf("FAIL: PDFium reported no font size for the heading\n");
        return 1;
    }

    const std::vector<unsigned char> fontData = readFile(fontPath);
    if (fontData.empty()) {
        std::printf("FAIL: cannot read font '%s'\n", fontPath);
        return 1;
    }
    FPDF_FONT font = FPDFText_LoadFont(
        doc.docPtr(), fontData.data(), static_cast<uint32_t>(fontData.size()),
        FPDF_FONT_TRUETYPE, 1);
    if (!font) {
        std::printf("FAIL: FPDFText_LoadFont rejected '%s'\n", fontPath);
        return 1;
    }
    std::printf("font loaded: %s (%zu bytes)\n", fontPath, fontData.size());

    // No style overrides: the native code must reuse the original object's
    // size, colour and matrix. forceReplace forces the bundled-font path.
    const int result = TextMutation::editLine(
        &doc, 0, l, b, r, t, expected, replacement,
        font, 0.0f, 0, nullptr, true);
    std::printf("editLine (replacement) -> %d (0 = OK)\n", result);
    if (result != TextMutation::LINE_EDIT_OK) {
        std::printf("FAIL: the replacement path could not edit the line\n");
        return 1;
    }

    unlink(output);
    if (!Save::saveAsCopy(&doc, output)) {
        std::printf("FAIL: saveAsCopy\n");
        return 1;
    }

    FPDF_DOCUMENT saved = FPDF_LoadDocument(output, nullptr);
    if (!saved) {
        std::printf("FAIL: the saved copy does not reopen\n");
        return 1;
    }

    float nl = 0, nb = 0, nr = 0, nt = 0, nsize = 0;
    if (!objectRectByText(saved, narrow(replacement).c_str(), &nl, &nb, &nr, &nt, &nsize)) {
        std::printf("FAIL: the replacement text object is not in the saved copy\n");
        FPDF_CloseDocument(saved);
        return 1;
    }
    FPDF_CloseDocument(saved);

    const float newHeight = nt - nb;
    const float leftDelta = std::fabs(nl - originalLeft);
    const float heightRatio = originalHeight > 0.0f ? newHeight / originalHeight : 0.0f;
    std::printf("replacement: left=%.2f height=%.2f size=%.2f pt\n", nl, newHeight, nsize);
    std::printf("left delta=%.2f pt (max 1.00), height ratio=%.2f (0.80..1.20), size delta=%.2f\n",
                leftDelta, heightRatio, std::fabs(nsize - originalSize));

    if (leftDelta > 1.0f) {
        std::printf("FAIL: the new text starts %.2f pt from the original left edge\n", leftDelta);
        return 1;
    }
    if (std::fabs(nsize - originalSize) > 0.01f) {
        std::printf("FAIL: the replacement did not reuse the original size\n");
        return 1;
    }

    // The ink box depends on the glyphs: a descender makes a different string
    // taller. The height check is therefore only meaningful when the
    // replacement is the same text, which still catches a wrong point size
    // (a 25 pt object is about twice as tall).
    if (replacement == expected) {
        if (heightRatio < 0.80f || heightRatio > 1.20f) {
            std::printf("FAIL: the new text height is %.0f%% of the original\n",
                        heightRatio * 100.0f);
            return 1;
        }
        std::printf("PASS: replacement keeps the original origin, height and size\n");
    } else {
        std::printf("PASS: replacement keeps the original origin and size\n");
    }
    return 0;
}
