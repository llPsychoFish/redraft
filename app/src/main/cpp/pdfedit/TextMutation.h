#ifndef TEXT_MUTATION_H
#define TEXT_MUTATION_H

#include "EditingDocument.h"
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <fpdfview.h>
#include <string>

namespace TextMutation {

// Path A: replace the text of an existing text object with FPDFText_SetText.
// Returns false when the object is not a text object or PDFium rejects the text.
bool setText(EditingDocument* doc, int pageIndex, int objectIndex,
             const std::u16string& newText);

// Path B: replace the object with a new text object that uses |font| (or the
// old object's own font when |font| is null), preserving its matrix, size and
// fill colour. Returns false on any failure; on failure the page is unchanged.
bool replaceTextObject(EditingDocument* doc, int pageIndex, int objectIndex,
                       const std::u16string& newText,
                       FPDF_FONT font, float fontSizePt,
                       int color, const float* matrix);

// Number of page objects (the index domain used by the JNI API).
int getTextObjectCount(EditingDocument* doc, int pageIndex);

// Length, in UTF-16 code units, of one text object's text.
int getTextObjectCharCount(EditingDocument* doc, int pageIndex, int objectIndex);

// The single TEXT object whose bounds overlap the given rectangle (PDF
// coordinates: left, bottom, right, top) and whose text equals |text| after
// whitespace normalisation. Returns -1 when zero or more than one object match.
int findTextObject(EditingDocument* doc, int pageIndex,
                   float left, float bottom, float right, float top,
                   const std::u16string& text);

} // namespace TextMutation

#endif // TEXT_MUTATION_H
