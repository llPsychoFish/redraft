#ifndef TEXT_MUTATION_H
#define TEXT_MUTATION_H

#include "EditingDocument.h"
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <string>

namespace TextMutation {

// Path A: Try in-place text set on existing text object
// Returns true on success, false if font cannot encode new text or other failure
bool setText(EditingDocument* doc, int pageIndex, int objectIndex,
             const std::u16string& newText);

// Path B: Replace text object with new one using bundled font
// Removes old object, creates new text object with same matrix/size/color
void replaceTextObject(EditingDocument* doc, int pageIndex, int objectIndex,
                       const std::u16string& newText,
                       FPDF_FONT font, float fontSizePt,
                       int color, const float* matrix); // CTM 6 floats: [a, b, c, d, e, f]

// Get text object count for a page (for matching charIndex to objectIndex)
int getTextObjectCount(EditingDocument* doc, int pageIndex);

// Get character count for a specific text object
int getTextObjectCharCount(EditingDocument* doc, int pageIndex, int objectIndex);

} // namespace TextMutation

#endif // TEXT_MUTATION_H