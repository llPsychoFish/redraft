#ifndef TEXT_MUTATION_H
#define TEXT_MUTATION_H

#include "EditingDocument.h"
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <fpdfview.h>
#include <string>
#include <vector>

namespace TextMutation {

// Result of editing a whole visual line. Mirrors the Kotlin LineEditOutcome.
enum LineEditResult {
    LINE_EDIT_OK = 0,
    LINE_EDIT_NO_OBJECTS = 1,      // nothing on the line could be matched
    LINE_EDIT_TEXT_MISMATCH = 2,   // the pieces do not add up to the line text
    LINE_EDIT_SET_TEXT_FAILED = 3, // neither SetText nor the replacement worked
    LINE_EDIT_GENERATE_FAILED = 4, // the page content could not be regenerated
};

// One text object that belongs to a visual line.
struct LinePiece {
    int index;      // page object index at the time of collection
    float left;
    float bottom;
    float right;
    float top;
    float fontSize;
    std::u16string text;
};

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

// Every TEXT object on the line, sorted left to right. An object belongs to the
// line when at least 60% of the OBJECT's own area lies inside the line rect.
std::vector<LinePiece> linePieces(EditingDocument* doc, int pageIndex,
                                  float left, float bottom, float right, float top);

// The line's text, built from the pieces left to right with one space inserted
// where the visual gap is wider than 0.12 em. This is the editor's start text.
std::u16string lineText(const std::vector<LinePiece>& pieces);

// Edit the whole line. The concatenated piece texts must equal
// |expectedLineText| with all whitespace removed; otherwise nothing is changed.
// The leftmost piece receives the whole new text; the other pieces are removed.
// When |forceReplace| is true (a formatting change) the first piece is always
// recreated with |font|/|fontSizePt|/|color| instead of reusing its own font.
int editLine(EditingDocument* doc, int pageIndex,
             float left, float bottom, float right, float top,
             const std::u16string& expectedLineText,
             const std::u16string& newText,
             FPDF_FONT font, float fontSizePt,
             int color, const float* matrix, bool forceReplace);

} // namespace TextMutation

#endif // TEXT_MUTATION_H
