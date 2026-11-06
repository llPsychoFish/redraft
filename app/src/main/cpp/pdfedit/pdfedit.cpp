#include <jni.h>
#include "pdfedit.h"
#include "EditingDocument.h"
#include "TextMutation.h"
#include "FontLoading.h"
#include "Save.h"
#include <string>

// Handle types (opaque pointers)
using EditingHandle = EditingDocument*;
using FontHandle = FPDF_FONT;

// Helper: UTF-8 to UTF-16LE conversion
static std::u16string utf8ToUtf16(const char* utf8) {
    // JNI GetStringUTFChars returns modified UTF-8, but we need standard UTF-8 to UTF-16
    // For simplicity, we assume ASCII-compatible input (which is the case for our use)
    std::u16string result;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(utf8);
    while (*p) {
        unsigned int cp = *p++;
        if (cp < 0x80) {
            result.push_back(static_cast<char16_t>(cp));
        } else if ((cp & 0xE0) == 0xC0) {
            // 2-byte sequence
            unsigned int cp2 = *p++ & 0x3F;
            result.push_back(static_cast<char16_t>(((cp & 0x1F) << 6) | cp2));
        } else if ((cp & 0xF0) == 0xE0) {
            // 3-byte sequence
            unsigned int cp2 = *p++ & 0x3F;
            unsigned int cp3 = *p++ & 0x3F;
            result.push_back(static_cast<char16_t>(((cp & 0x0F) << 12) | (cp2 << 6) | cp3));
        } else if ((cp & 0xF8) == 0xF0) {
            // 4-byte sequence (surrogate pair needed for UTF-16)
            unsigned int cp2 = *p++ & 0x3F;
            unsigned int cp3 = *p++ & 0x3F;
            unsigned int cp4 = *p++ & 0x3F;
            unsigned int codepoint = ((cp & 0x07) << 18) | (cp2 << 12) | (cp3 << 6) | cp4;
            if (codepoint > 0xFFFF) {
                codepoint -= 0x10000;
                result.push_back(static_cast<char16_t>(0xD800 | (codepoint >> 10)));
                result.push_back(static_cast<char16_t>(0xDC00 | (codepoint & 0x3FF)));
            } else {
                result.push_back(static_cast<char16_t>(codepoint));
            }
        }
    }
    return result;
}

// 1. Open editing document from file URI (SAF file descriptor)
extern "C" JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeOpen(
    JNIEnv* env, jclass, jstring uri, jobject fd, jlong offset, jlong length) {
    const char* uriStr = env->GetStringUTFChars(uri, nullptr);
    // Duplicate FD for our own use
    int dupFd = dup(env->GetDirectBufferAddress(fd));
    EditingDocument* doc = new EditingDocument(dupFd, offset, length);
    env->ReleaseStringUTFChars(uri, uriStr);
    return reinterpret_cast<jlong>(doc);
}

// 2. Close editing document
extern "C" JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<EditingHandle>(handle);
}

// 3. Try in-place text set (Path A)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeSetText(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex, jstring newText) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* text = env->GetStringUTFChars(newText, nullptr);
    // Convert UTF-8 to UTF-16LE for FPDFText_SetText (expects FPDF_WIDESTRING)
    std::u16string wideText = utf8ToUtf16(text);
    bool success = TextMutation::setText(doc, pageIndex, objectIndex, wideText);
    env->ReleaseStringUTFChars(newText, text);
    return success;
}

// 4. Replace text object (Path B)
extern "C" JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeReplaceTextObject(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex,
    jstring newText, jstring fontName, jfloat fontSizePt, jint color, jfloatArray matrix) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* text = env->GetStringUTFChars(newText, nullptr);
    const char* font = env->GetStringUTFChars(fontName, nullptr);
    std::u16string wideText = utf8ToUtf16(text);
    std::u16string wideFont = utf8ToUtf16(font);
    jfloat* matrixArr = env->GetFloatArrayElements(matrix, nullptr);
    TextMutation::replaceTextObject(doc, pageIndex, objectIndex, wideText,
        wideFont, fontSizePt, color, matrixArr);
    env->ReleaseStringUTFChars(newText, text);
    env->ReleaseStringUTFChars(fontName, font);
    env->ReleaseFloatArrayElements(matrix, matrixArr, 0);
}

// 5. Save as copy to temp file
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeSaveAsCopy(
    JNIEnv* env, jclass, jlong handle, jstring destPath) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* path = env->GetStringUTFChars(destPath, nullptr);
    bool success = Save::saveAsCopy(doc, path);
    env->ReleaseStringUTFChars(destPath, path);
    return success;
}

// 6. Load bundled font from asset byte array
extern "C" JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeLoadBundledFont(
    JNIEnv* env, jclass, jlong handle, jstring fontName, jboolean bold, jboolean italic, jbyteArray fontData) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    const char* name = env->GetStringUTFChars(fontName, nullptr);
    
    // Get byte array data
    jsize length = env->GetArrayLength(fontData);
    jbyte* data = env->GetByteArrayElements(fontData, nullptr);
    std::vector<uint8_t> buffer(length);
    memcpy(buffer.data(), data, length);
    env->ReleaseByteArrayElements(fontData, data, JNI_ABORT);
    
    std::u16string wideName = utf8ToUtf16(name);
    FPDF_FONT font = FontLoading::loadBundledFontFromBuffer(doc, wideName, buffer);
    env->ReleaseStringUTFChars(fontName, name);
    return reinterpret_cast<jlong>(font);
}

// 7. Get text object count for a page (for matching)
extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeGetTextObjectCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    return TextMutation::getTextObjectCount(doc, pageIndex);
}

// 8. Get char count for a text object (for matching)
extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeGetTextObjectCharCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex, jint objectIndex) {
    EditingHandle doc = reinterpret_cast<EditingHandle>(handle);
    return TextMutation::getTextObjectCharCount(doc, pageIndex, objectIndex);
}