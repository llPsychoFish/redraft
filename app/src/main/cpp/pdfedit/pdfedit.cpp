#include <jni.h>
#include "pdfedit.h"
#include "EditingDocument.h"
#include "TextMutation.h"
#include "FontLoading.h"
#include "Save.h"

#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <string>
#include <vector>

#define LOG_TAG "RedraftNative"
#define LOG_W(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using EditingHandle = EditingDocument*;

// JNI strings are UTF-16; take them as such instead of re-parsing modified UTF-8.
static std::u16string jstringToUtf16(JNIEnv* env, jstring value) {
    if (!value) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    const jsize length = env->GetStringLength(value);
    std::u16string result(reinterpret_cast<const char16_t*>(chars),
                          static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeOpen(
    JNIEnv* env, jclass, jstring, jobject fd, jlong offset, jlong length) {
    try {
        if (!fd) return 0;
        jclass cls = env->GetObjectClass(fd);
        if (!cls) return 0;
        jmethodID getFd = env->GetMethodID(cls, "getFd", "()I");
        if (!getFd) return 0;

        const jint rawFd = env->CallIntMethod(fd, getFd);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return 0;
        }
        if (rawFd < 0) return 0;

        // SAF providers do not always know the size (statSize == -1), so the
        // descriptor itself is the authority when the length is missing.
        long resolvedLength = static_cast<long>(length);
        if (resolvedLength <= 0) {
            struct stat fileStat = {};
            if (fstat(rawFd, &fileStat) == 0) resolvedLength = static_cast<long>(fileStat.st_size);
        }
        if (resolvedLength <= 0) {
            LOG_W("nativeOpen: the document length is unknown");
            return 0;
        }

        auto* document = new EditingDocument(static_cast<int>(rawFd),
                                             static_cast<long>(offset),
                                             resolvedLength);
        if (!document->docPtr()) {
            LOG_W("FPDF_LoadCustomDocument failed (error %lu)", FPDF_GetLastError());
            delete document;
            return 0;
        }
        return reinterpret_cast<jlong>(document);
    } catch (...) {
        LOG_W("nativeOpen threw");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    try {
        delete reinterpret_cast<EditingHandle>(handle);
    } catch (...) {
        LOG_W("nativeClose threw");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeSetText(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex, jstring newText) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return JNI_FALSE;
        const std::u16string text = jstringToUtf16(env, newText);
        return TextMutation::setText(document, pageIndex, objectIndex, text)
                   ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        LOG_W("nativeSetText threw");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeReplaceTextObject(
    JNIEnv* env, jclass, jlong handle, jint pageIndex, jint objectIndex,
    jstring newText, jlong fontHandle, jfloat fontSizePt, jint color, jfloatArray matrix) {
    jfloat* matrixValues = nullptr;
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return JNI_FALSE;

        const std::u16string text = jstringToUtf16(env, newText);
        if (matrix) {
            matrixValues = env->GetFloatArrayElements(matrix, nullptr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                matrixValues = nullptr;
            }
        }

        const bool ok = TextMutation::replaceTextObject(
            document, pageIndex, objectIndex, text,
            reinterpret_cast<FPDF_FONT>(fontHandle),
            static_cast<float>(fontSizePt), static_cast<int>(color), matrixValues);

        if (matrixValues) {
            env->ReleaseFloatArrayElements(matrix, matrixValues, JNI_ABORT);
        }
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (matrixValues) {
            env->ReleaseFloatArrayElements(matrix, matrixValues, JNI_ABORT);
        }
        LOG_W("nativeReplaceTextObject threw");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeSaveAsCopy(
    JNIEnv* env, jclass, jlong handle, jstring destPath) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document || !destPath) return JNI_FALSE;
        const char* path = env->GetStringUTFChars(destPath, nullptr);
        if (!path) return JNI_FALSE;
        const bool ok = Save::saveAsCopy(document, path);
        env->ReleaseStringUTFChars(destPath, path);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        LOG_W("nativeSaveAsCopy threw");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeLoadBundledFont(
    JNIEnv* env, jclass, jlong handle, jstring fontName, jboolean, jboolean, jbyteArray fontData) {
    jbyte* data = nullptr;
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document || !fontData) return 0;

        const jsize length = env->GetArrayLength(fontData);
        if (length <= 0) return 0;
        data = env->GetByteArrayElements(fontData, nullptr);
        if (!data) return 0;

        std::vector<uint8_t> buffer(static_cast<size_t>(length));
        memcpy(buffer.data(), data, static_cast<size_t>(length));
        env->ReleaseByteArrayElements(fontData, data, JNI_ABORT);
        data = nullptr;

        const std::u16string name = jstringToUtf16(env, fontName);
        FPDF_FONT font = FontLoading::loadBundledFontFromBuffer(document, name, buffer);
        if (!font) {
            LOG_W("FPDFText_LoadFont failed");
            return 0;
        }
        return reinterpret_cast<jlong>(font);
    } catch (...) {
        if (data) env->ReleaseByteArrayElements(fontData, data, JNI_ABORT);
        LOG_W("nativeLoadBundledFont threw");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeGetTextObjectCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return 0;
        return TextMutation::getTextObjectCount(document, pageIndex);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeGetTextObjectCharCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex, jint objectIndex) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return 0;
        return TextMutation::getTextObjectCharCount(document, pageIndex, objectIndex);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeFindTextObject(
    JNIEnv* env, jclass, jlong handle, jint pageIndex,
    jfloat left, jfloat top, jfloat right, jfloat bottom, jstring text) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return -1;
        const std::u16string wanted = jstringToUtf16(env, text);
        return TextMutation::findTextObject(document, pageIndex,
                                            left, top, right, bottom, wanted);
    } catch (...) {
        LOG_W("nativeFindTextObject threw");
        return -1;
    }
}

static jstring utf16ToJString(JNIEnv* env, const std::u16string& text) {
    return env->NewString(reinterpret_cast<const jchar*>(text.data()),
                          static_cast<jsize>(text.size()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeLineText(
    JNIEnv* env, jclass, jlong handle, jint pageIndex,
    jfloat left, jfloat top, jfloat right, jfloat bottom) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return env->NewStringUTF("");
        const auto pieces = TextMutation::linePieces(document, pageIndex,
                                                     left, top, right, bottom);
        return utf16ToJString(env, TextMutation::lineText(pieces));
    } catch (...) {
        LOG_W("nativeLineText threw");
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeLineObjectCount(
    JNIEnv*, jclass, jlong handle, jint pageIndex,
    jfloat left, jfloat top, jfloat right, jfloat bottom) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return 0;
        return static_cast<jint>(TextMutation::linePieces(document, pageIndex,
                                                          left, top, right, bottom).size());
    } catch (...) {
        LOG_W("nativeLineObjectCount threw");
        return 0;
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeLineFontSize(
    JNIEnv*, jclass, jlong handle, jint pageIndex,
    jfloat left, jfloat top, jfloat right, jfloat bottom) {
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return 0.0f;
        return static_cast<jfloat>(TextMutation::lineFontSize(document, pageIndex,
                                                              left, top, right, bottom));
    } catch (...) {
        LOG_W("nativeLineFontSize threw");
        return 0.0f;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redraft_pdfedit_PdfEditingEngineImpl_nativeEditLine(
    JNIEnv* env, jclass, jlong handle, jint pageIndex,
    jfloat left, jfloat top, jfloat right, jfloat bottom,
    jstring expectedText, jstring newText, jlong fontHandle,
    jfloat fontSizePt, jint color, jfloatArray matrix, jboolean forceReplace) {
    jfloat* matrixValues = nullptr;
    try {
        auto* document = reinterpret_cast<EditingHandle>(handle);
        if (!document) return TextMutation::LINE_EDIT_NO_OBJECTS;

        const std::u16string expected = jstringToUtf16(env, expectedText);
        const std::u16string replacement = jstringToUtf16(env, newText);
        if (matrix) {
            matrixValues = env->GetFloatArrayElements(matrix, nullptr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                matrixValues = nullptr;
            }
        }

        const int result = TextMutation::editLine(
            document, pageIndex, left, top, right, bottom,
            expected, replacement,
            reinterpret_cast<FPDF_FONT>(fontHandle),
            static_cast<float>(fontSizePt), static_cast<int>(color),
            matrixValues, forceReplace == JNI_TRUE);

        if (matrixValues) {
            env->ReleaseFloatArrayElements(matrix, matrixValues, JNI_ABORT);
        }
        return result;
    } catch (...) {
        if (matrixValues) {
            env->ReleaseFloatArrayElements(matrix, matrixValues, JNI_ABORT);
        }
        LOG_W("nativeEditLine threw");
        return TextMutation::LINE_EDIT_SET_TEXT_FAILED;
    }
}
