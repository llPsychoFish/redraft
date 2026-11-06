#ifndef EDITING_DOCUMENT_H
#define EDITING_DOCUMENT_H

#include <fpdfview.h>

class EditingDocument {
public:
    EditingDocument(int fd, long offset, long length);
    ~EditingDocument();

    FPDF_DOCUMENT docPtr() const { return doc_; }
    int pageCount() const;
    FPDF_PAGE loadPage(int index) const;
    void closePage(FPDF_PAGE page) const;

    // No copying — unique ownership
    EditingDocument(const EditingDocument&) = delete;
    EditingDocument& operator=(const EditingDocument&) = delete;

private:
    FPDF_DOCUMENT doc_ = nullptr;
    int fd_ = -1;
    long offset_ = 0;
    long length_ = 0;
};

#endif // EDITING_DOCUMENT_H