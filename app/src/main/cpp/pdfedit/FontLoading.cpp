#include "FontLoading.h"
#include <fpdf_edit.h>
#include <fpdfview.h>
#include <vector>

namespace FontLoading {

FPDF_FONT loadBundledFontFromBuffer(EditingDocument* doc, const std::u16string& /*fontName*/,
                                    const std::vector<uint8_t>& fontData) {
    if (!doc || !doc->docPtr() || fontData.empty()) return nullptr;

    // The bundled Liberation TTFs are TrueType, not CID-keyed fonts.
    return FPDFText_LoadFont(doc->docPtr(), fontData.data(),
                             static_cast<uint32_t>(fontData.size()),
                             FPDF_FONT_TRUETYPE, 0);
}

} // namespace FontLoading
