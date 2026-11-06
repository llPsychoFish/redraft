#include "TextMutation.h"
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <fpdfview.h>
#include <vector>

namespace TextMutation {

bool setText(EditingDocument* doc, int pageIndex, int objectIndex,
             const std::u16string& newText) {
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return false;

    int objCount = FPDFPage_CountObjects(page);
    if (objectIndex >= objCount) {
        doc->closePage(page);
        return false;
    }

    FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, objectIndex);
    if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) {
        doc->closePage(page);
        return false;
    }

    // Path A: FPDFText_SetText on existing text object
    // Requires: font can encode all characters (checked in Kotlin before calling)
    FPDF_WIDESTRING wideText = reinterpret_cast<FPDF_WIDESTRING>(const_cast<char16_t*>(newText.c_str()));
    bool success = FPDFText_SetText(obj, wideText);

    if (success) {
        FPDFPage_GenerateContent(page); // Regenerate content stream
    }
    doc->closePage(page);
    return success;
}

void replaceTextObject(EditingDocument* doc, int pageIndex, int objectIndex,
                       const std::u16string& newText,
                       const std::u16string& fontName, float fontSizePt,
                       int color, const float* matrix) {
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return;

    // Remove old object
    FPDF_PAGEOBJECT oldObj = FPDFPage_GetObject(page, objectIndex);
    FPDFPage_RemoveObject(page, oldObj);
    // Ownership: removed object must be destroyed if not re-inserted
    FPDFPageObj_Destroy(oldObj);

    // Create new text object
    FPDF_PAGEOBJECT newObj = FPDFPageObj_CreateTextObj(doc->docPtr(), 0, 0);
    FPDFText_SetText(newObj, reinterpret_cast<FPDF_WIDESTRING>(const_cast<char16_t*>(newText.c_str())));
    FPDFText_SetFontSize(newObj, fontSizePt);
    
    // Extract ARGB components (color is 0xAARRGGBB)
    unsigned char alpha = (color >> 24) & 0xFF;
    unsigned char red = (color >> 16) & 0xFF;
    unsigned char green = (color >> 8) & 0xFF;
    unsigned char blue = color & 0xFF;
    FPDFText_SetFillColor(newObj, red, green, blue, alpha);

    // Apply matrix (CTM) — 6 floats: [a, b, c, d, e, f]
    FS_MATRIX ctm = {matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]};
    FPDFPageObj_SetMatrix(newObj, &ctm);

    // Load font (bundled Liberation)
    // Note: The font is loaded in Kotlin and the handle passed in,
    // but for simplicity here we load it directly from the document.
    // In practice, the FontHandle should be managed by the Kotlin side.
    // This is a simplified version for the tracer.
    FPDF_FONT font = FPDFText_LoadStandardFont(doc->docPtr(), 
        reinterpret_cast<const unsigned short*>(fontName.c_str()), 0);
    if (font) {
        FPDFText_SetFont(newObj, font);
    }

    FPDFPage_InsertObject(page, newObj);
    FPDFPage_GenerateContent(page);

    // Font handle owned by document; closed when document closes
    doc->closePage(page);
}

int getTextObjectCount(EditingDocument* doc, int pageIndex) {
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return 0;
    int count = FPDFPage_CountObjects(page);
    doc->closePage(page);
    return count;
}

int getTextObjectCharCount(EditingDocument* doc, int pageIndex, int objectIndex) {
    FPDF_PAGE page = doc->loadPage(pageIndex);
    if (!page) return 0;

    int objCount = FPDFPage_CountObjects(page);
    if (objectIndex >= objCount) {
        doc->closePage(page);
        return 0;
    }

    FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, objectIndex);
    if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) {
        doc->closePage(page);
        return 0;
    }

    // Load text page to count characters in this text object
    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    int charCount = 0;
    if (textPage) {
        charCount = FPDFText_CountChars(textPage);
        FPDFText_ClosePage(textPage);
    }
    doc->closePage(page);
    return charCount;
}

} // namespace TextMutation