#include "EditingDocument.h"

#include <unistd.h>

EditingDocument::EditingDocument(int fd, long offset, long length)
    : fd_(dup(fd)), offset_(offset), length_(length) {
    if (fd_ < 0 || length_ <= 0) return;

    // PDFium copies the FPDF_FILEACCESS structure, but the callback's |param|
    // (this document) must outlive the document, which it does.
    FPDF_FILEACCESS fileAccess = {};
    fileAccess.m_FileLen = static_cast<unsigned long>(length_);
    fileAccess.m_GetBlock = &EditingDocument::ReadBlock;
    fileAccess.m_Param = this;
    doc_ = FPDF_LoadCustomDocument(&fileAccess, nullptr);
}

EditingDocument::~EditingDocument() {
    if (doc_) FPDF_CloseDocument(doc_);
    if (fd_ >= 0) close(fd_);
}

int EditingDocument::ReadBlock(void* param, unsigned long pos,
                               unsigned char* buffer, unsigned long size) {
    auto* document = static_cast<EditingDocument*>(param);
    if (!document || document->fd_ < 0 || !buffer) return 0;

    const off_t target = static_cast<off_t>(document->offset_ + static_cast<long>(pos));
    if (lseek(document->fd_, target, SEEK_SET) == static_cast<off_t>(-1)) return 0;

    unsigned long remaining = size;
    unsigned char* cursor = buffer;
    while (remaining > 0) {
        const ssize_t readBytes = read(document->fd_, cursor, remaining);
        if (readBytes <= 0) return 0;
        cursor += readBytes;
        remaining -= static_cast<unsigned long>(readBytes);
    }
    return 1;
}

int EditingDocument::pageCount() const {
    if (!doc_) return 0;
    return FPDF_GetPageCount(doc_);
}

FPDF_PAGE EditingDocument::loadPage(int index) const {
    if (!doc_ || index < 0 || index >= pageCount()) return nullptr;
    return FPDF_LoadPage(doc_, index);
}

void EditingDocument::closePage(FPDF_PAGE page) const {
    if (page) FPDF_ClosePage(page);
}
