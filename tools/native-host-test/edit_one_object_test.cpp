#include "EditingDocument.h"
#include "TextMutation.h"
#include "Save.h"
#include <fpdfview.h>
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

static std::u16string u16(const char* s) { std::u16string o; for (; *s; ++s) o.push_back((unsigned char)*s); return o; }

int main(int argc, char** argv) {
    // args: pdf l b r t "full line text" "new text" out.pdf
    FPDF_InitLibrary();
    const char* path = argv[1];
    float l = atof(argv[2]), b = atof(argv[3]), r = atof(argv[4]), t = atof(argv[5]);
    int fd = open(path, O_RDONLY); struct stat st; fstat(fd, &st);
    EditingDocument doc(fd, 0, st.st_size);
    printf("doc opened: pages=%d\n", doc.pageCount());
    int idx = TextMutation::findTextObject(&doc, 0, l, b, r, t, u16(argv[6]));
    printf("findTextObject -> %d\n", idx);
    if (idx < 0) return 2;
    bool ok = TextMutation::setText(&doc, 0, idx, u16(argv[7]));
    printf("setText (path A) -> %d\n", ok);
    if (!ok) {
        float m[6] = {1,0,0,1,0,0};
        ok = TextMutation::replaceTextObject(&doc, 0, idx, u16(argv[7]), nullptr, 0.f, 0, m);
        printf("replaceTextObject (path B, same font) -> %d\n", ok);
    }
    bool saved = Save::saveAsCopy(&doc, argv[8]);
    printf("saveAsCopy -> %d\n", saved);
    return ok && saved ? 0 : 3;
}
