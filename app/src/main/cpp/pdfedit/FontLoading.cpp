#include "FontLoading.h"
#include <fpdf_edit.h>
#include <fpdfview.h>
#include <vector>

namespace FontLoading {

FPDF_FONT loadBundledFontFromBuffer(EditingDocument* doc, const std::u16string& fontName,
                                    const std::vector<uint8_t>& fontData) {
    // Load font into the editing document from memory buffer
    FPDF_FONT font = FPDFText_LoadFont(doc->docPtr(), fontData.data(), fontData.size());
    return font; // Document owns the font; closed when document closes
}

} // namespace FontLoading