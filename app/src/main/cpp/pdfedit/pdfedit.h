#ifndef PDFEDIT_H
#define PDFEDIT_H

#include <jni.h>
#include <fpdfview.h>
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <fpdf_save.h>
#include <fpdf_ext.h>

#ifdef __cplusplus
extern "C" {
#endif

// 1. Open editing document from file URI (SAF file descriptor)
JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeOpen(
    JNIEnv* env, jclass, jstring uri, jobject fd, jlong offset, jlong length);

// 2. Close editing document
JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeClose(
    JNIEnv*, jclass, jlong handle);

// 3. Try in-place text set (Path A) — returns Boolean
JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeSetText(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex, jstring newText);

// 4. Replace text object (Path B) — void, throws on failure
JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeReplaceTextObject(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex,
    jstring newText, jstring fontName, jfloat fontSizePt, jint color, jfloatArray matrix);

// 5. Save as copy to temp file
JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeSaveAsCopy(
    JNIEnv* env, jclass, jlong handle, jstring destPath);

// 6. Load bundled font from asset byte array
JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeLoadBundledFont(
    JNIEnv* env, jclass, jlong handle, jstring fontName, jboolean bold, jboolean italic, jbyteArray fontData);

// 7. Get text object count for a page (for matching)
JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeGetTextObjectCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex);

// 8. Get char count for a text object (for matching)
JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngine_nativeGetTextObjectCharCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex, jint objectIndex);

#ifdef __cplusplus
}
#endif

#endif // PDFEDIT_H