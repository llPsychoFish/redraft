#include "Save.h"
#include <fpdf_save.h>
#include <cstdio>

namespace Save {

bool saveAsCopy(EditingDocument* doc, const char* destPath) {
    // FPDF_SaveAsCopy with FPDF_INCREMENTAL for efficiency
    FPDF_FILEWRITE fileWrite = {0};
    fileWrite.WriteBlock = [](void* param, const void* data, unsigned long size) -> int {
        FILE* f = static_cast<FILE*>(param);
        return fwrite(data, 1, size, f) == size ? 1 : 0;
    };
    FILE* f = fopen(destPath, "wb");
    if (!f) return false;
    fileWrite.m_Param = f;
    int result = FPDF_SaveAsCopy(doc->docPtr(), &fileWrite, FPDF_INCREMENTAL);
    fclose(f);
    return result != 0;
}

} // namespace Save