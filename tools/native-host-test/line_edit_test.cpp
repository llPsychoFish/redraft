// Host test for the app's whole-line editing path (TextMutation::editLine).
// Edits a heading that may be ONE object (A_single.pdf) or one object per WORD
// (B_perword.pdf), saves a copy, re-reads it, and checks the refusal path.
#include "EditingDocument.h"
#include "TextMutation.h"
#include "Save.h"
#include <fpdfview.h>
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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

static std::string documentText(FPDF_DOCUMENT doc) {
    FPDF_PAGE page = FPDF_LoadPage(doc, 0);
    if (!page) return {};
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(page);
    if (!tp) { FPDF_ClosePage(page); return {}; }
    const int count = FPDFText_CountChars(tp);
    std::string out;
    for (int i = 0; i < count; ++i) {
        unsigned int c = FPDFText_GetUnicode(tp, i);
        if (c) out.push_back(c < 128 ? static_cast<char>(c) : '?');
    }
    FPDFText_ClosePage(tp);
    FPDF_ClosePage(page);
    return out;
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

int main(int argc, char** argv) {
    // args: input.pdf output.pdf "new text"
    if (argc < 4) {
        std::fprintf(stderr, "usage: line_edit_test in.pdf out.pdf \"new text\"\n");
        return 64;
    }
    FPDF_InitLibrary();

    const char* input = argv[1];
    const char* output = argv[2];
    const std::u16string expected = u16("KOFORIDUA TECHNICAL UNIVERSITY");
    const std::u16string replacement = u16(argv[3]);

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
    std::printf("heading rect: %.1f %.1f %.1f %.1f\n", l, b, r, t);

    // The editor's starting text must read with both word spaces, whether the
    // heading is one object or one object per word.
    const auto pieces = TextMutation::linePieces(&doc, 0, l, b, r, t);
    const std::string editorText = narrow(TextMutation::lineText(pieces));
    std::printf("objects on line: %zu, editor text: '%s'\n", pieces.size(), editorText.c_str());
    if (editorText != narrow(expected)) {
        std::printf("FAIL: editor text is not the heading with both spaces\n");
        return 1;
    }

    const int result = TextMutation::editLine(
        &doc, 0, l, b, r, t, expected, replacement,
        nullptr, 0.0f, static_cast<int>(0xFF000000), nullptr, false);
    std::printf("editLine -> %d (0 = OK)\n", result);
    if (result != TextMutation::LINE_EDIT_OK) {
        std::printf("FAIL: the line could not be edited\n");
        return 1;
    }
    unlink(output);
    if (!Save::saveAsCopy(&doc, output)) {
        std::printf("FAIL: saveAsCopy\n");
        return 1;
    }

    // 1. The new text must be in the saved copy, and the next line untouched.
    FPDF_DOCUMENT saved = FPDF_LoadDocument(output, nullptr);
    if (!saved) {
        std::printf("FAIL: the saved copy does not reopen\n");
        return 1;
    }
    const std::string text = documentText(saved);
    FPDF_CloseDocument(saved);
    const std::string wanted = narrow(replacement);
    if (text.find(wanted) == std::string::npos) {
        std::printf("FAIL: '%s' not found in the saved copy; text='%s'\n", wanted.c_str(), text.c_str());
        return 1;
    }
    if (text.find("Ghana Water Limited") == std::string::npos) {
        std::printf("FAIL: the neighbouring line changed; text='%s'\n", text.c_str());
        return 1;
    }
    if (text.find("KOFORIDUA") != std::string::npos) {
        std::printf("FAIL: the old heading is still present; text='%s'\n", text.c_str());
        return 1;
    }
    std::printf("PASS: saved copy has '%s' and keeps the next line\n", wanted.c_str());

    // 2. Refusal: pieces must add up to the expected line text.
    int fd2 = open(output, O_RDONLY);
    struct stat st2 {};
    fstat(fd2, &st2);
    EditingDocument again(fd2, 0, st2.st_size);
    const int refused = TextMutation::editLine(
        &again, 0, l, b, r, t,
        u16("TOTALLY DIFFERENT LINE"), u16("X"),
        nullptr, 0.0f, static_cast<int>(0xFF000000), nullptr, false);
    std::printf("refusal editLine -> %d (2 = text mismatch)\n", refused);
    if (refused != TextMutation::LINE_EDIT_TEXT_MISMATCH) {
        std::printf("FAIL: a mismatched line must be refused\n");
        return 1;
    }
    std::printf("PASS: refusal path\n");
    return 0;
}
