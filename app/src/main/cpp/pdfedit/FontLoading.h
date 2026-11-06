#ifndef FONT_LOADING_H
#define FONT_LOADING_H

#include "EditingDocument.h"
#include <fpdf_edit.h>
#include <string>
#include <vector>

namespace FontLoading {

// Load bundled font from byte buffer
// fontData is the raw TTF font data
// Returns FPDF_FONT handle owned by the document (closed when document closes)
FPDF_FONT loadBundledFontFromBuffer(EditingDocument* doc, const std::u16string& fontName,
                                    const std::vector<uint8_t>& fontData);

} // namespace FontLoading

#endif // FONT_LOADING_H