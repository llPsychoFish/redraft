#ifndef SAVE_H
#define SAVE_H

#include "EditingDocument.h"

namespace Save {

// Save editing document as copy to destination path
// Uses FPDF_SaveAsCopy with FPDF_INCREMENTAL for efficiency
// Returns true on success
bool saveAsCopy(EditingDocument* doc, const char* destPath);

} // namespace Save

#endif // SAVE_H