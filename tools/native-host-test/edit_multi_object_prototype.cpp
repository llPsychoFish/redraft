// Prototype: edit a visual line that is made of SEVERAL text objects.
#include "EditingDocument.h"
#include "Save.h"
#include <fpdfview.h>
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <algorithm>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>
#include <iterator>

static std::u16string u16(const char* s) { std::u16string o; for (; *s; ++s) o.push_back((unsigned char)*s); return o; }
static std::u16string compact(const std::u16string& in) { std::u16string o; for (char16_t c : in) if (c!=u' '&&c!=u'\t'&&c!=u'\r'&&c!=u'\n') o.push_back(c); return o; }

static std::u16string objText(FPDF_PAGEOBJECT obj, FPDF_TEXTPAGE tp) {
    unsigned long need = FPDFTextObj_GetText(obj, tp, nullptr, 0);
    if (need <= 2) return {};
    std::vector<FPDF_WCHAR> buf(need / 2 + 1);
    unsigned long w = FPDFTextObj_GetText(obj, tp, buf.data(), need);
    std::u16string s(reinterpret_cast<const char16_t*>(buf.data()), w / 2);
    while (!s.empty() && s.back() == 0) s.pop_back();
    return s;
}

struct Piece { int index; float l, b, r, t; std::u16string text; };

// All text objects that sit on the line: >= 60% of the OBJECT's own area inside the line rect.
static std::vector<Piece> piecesOnLine(FPDF_PAGE page, FPDF_TEXTPAGE tp, float L, float B, float R, float T) {
    std::vector<Piece> out;
    int n = FPDFPage_CountObjects(page);
    for (int i = 0; i < n; ++i) {
        FPDF_PAGEOBJECT o = FPDFPage_GetObject(page, i);
        if (!o || FPDFPageObj_GetType(o) != FPDF_PAGEOBJ_TEXT) continue;
        float l, b, r, t; if (!FPDFPageObj_GetBounds(o, &l, &b, &r, &t)) continue;
        float w = std::min(r, R) - std::max(l, L), h = std::min(t, T) - std::max(b, B);
        float area = (r - l) * (t - b);
        if (w <= 0 || h <= 0 || area <= 0) continue;
        if ((w * h) / area < 0.6f) continue;
        out.push_back({i, l, b, r, t, objText(o, tp)});
    }
    std::sort(out.begin(), out.end(), [](const Piece& a, const Piece& b) { return a.l < b.l; });
    return out;
}

int main(int argc, char** argv) {
    FPDF_InitLibrary();
    const char* path = argv[1];
    float L = atof(argv[2]), B = atof(argv[3]), R = atof(argv[4]), T = atof(argv[5]);
    std::u16string lineText = u16(argv[6]), newText = u16(argv[7]);
    int fd = open(path, O_RDONLY); struct stat st; fstat(fd, &st);
    EditingDocument doc(fd, 0, st.st_size);
    FPDF_PAGE page = doc.loadPage(0);
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(page);
    auto pieces = piecesOnLine(page, tp, L, B, R, T);
    std::u16string joined; for (auto& p : pieces) joined += p.text;
    printf("pieces on line: %zu\n", pieces.size());
    if (pieces.empty() || compact(joined) != compact(lineText)) { printf("REFUSE: pieces do not add up to the line text\n"); return 2; }

    // keep the leftmost, give it the whole new text, remove the rest (highest index first)
    FPDF_PAGEOBJECT keep = FPDFPage_GetObject(page, pieces[0].index);
    bool ok = FPDFText_SetText(keep, reinterpret_cast<FPDF_WIDESTRING>(newText.c_str())) != 0;
    printf("SetText on first piece -> %d\n", ok);
    std::vector<int> drop; for (size_t i = 1; i < pieces.size(); ++i) drop.push_back(pieces[i].index);
    std::sort(drop.rbegin(), drop.rend());
    for (int idx : drop) { FPDF_PAGEOBJECT o = FPDFPage_GetObject(page, idx); if (FPDFPage_RemoveObject(page, o)) FPDFPageObj_Destroy(o); }
    ok = ok && FPDFPage_GenerateContent(page);
    FPDFText_ClosePage(tp); doc.closePage(page);
    printf("generate -> %d, saved -> %d\n", ok, Save::saveAsCopy(&doc, argv[8]));
    return 0;
}
