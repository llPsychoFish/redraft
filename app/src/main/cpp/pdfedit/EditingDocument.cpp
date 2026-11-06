#include "EditingDocument.h"
#include <fpdfview.h>
#include <unistd.h>

EditingDocument::EditingDocument(int fd, long offset, long length)
    : fd_(dup(fd)), offset_(offset), length_(length) {
    // Use FPDF_LoadCustomDocument with a file-accessor that reads from the FD
    // at the given offset/length. This avoids copying the whole PDF to memory.
    FPDF_FILEACCESS fileAccess = {0};
    fileAccess.m_FileLen = length_;
    fileAccess.m_GetBlock = [](void* param, unsigned long pos,
                               unsigned char* buf, unsigned long size) -> int {
        int fd = *static_cast<int*>(param);
        off_t seekPos = lseek(fd, pos, SEEK_SET);
        if (seekPos == -1) return 0;
        ssize_t readBytes = read(fd, buf, size);
        return readBytes == static_cast<ssize_t>(size) ? 1 : 0;
    };
    fileAccess.m_Param = &fd_;
    doc_ = FPDF_LoadCustomDocument(&fileAccess, nullptr);
}

EditingDocument::~EditingDocument() {
    if (doc_) FPDF_CloseDocument(doc_);
    if (fd_ >= 0) close(fd_);
}

int EditingDocument::pageCount() const {
    return FPDF_GetPageCount(doc_);
}

FPDF_PAGE EditingDocument::loadPage(int index) const {
    return FPDF_LoadPage(doc_, index);
}

void EditingDocument::closePage(FPDF_PAGE page) const {
    FPDF_ClosePage(page);
}