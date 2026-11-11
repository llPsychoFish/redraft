#include "Save.h"
#include <fpdf_save.h>
#include <cstdio>

namespace Save {
namespace {

// FPDF_FILEWRITE has no user-data field: PDFium passes the writer back to the
// callback, so the FILE* lives in a struct that starts with the writer.
struct FileWriter {
    FPDF_FILEWRITE base;
    FILE* file;
};

int WriteBlock(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    auto* writer = reinterpret_cast<FileWriter*>(self);
    if (!writer || !writer->file) return 0;
    return fwrite(data, 1, size, writer->file) == size ? 1 : 0;
}

} // namespace

bool saveAsCopy(EditingDocument* doc, const char* destPath) {
    if (!doc || !doc->docPtr() || !destPath) return false;

    FILE* file = fopen(destPath, "wb");
    if (!file) return false;

    FileWriter writer = {};
    writer.base.version = 1;
    writer.base.WriteBlock = &WriteBlock;
    writer.file = file;

    // A full save (flags = 0), not FPDF_INCREMENTAL: the destination is a new
    // file, and an incremental save assumes the original bytes are already there.
    int result = FPDF_SaveAsCopy(doc->docPtr(), &writer.base, 0);
    if (fflush(file) != 0) result = 0;
    if (fclose(file) != 0) result = 0;
    return result != 0;
}

} // namespace Save
