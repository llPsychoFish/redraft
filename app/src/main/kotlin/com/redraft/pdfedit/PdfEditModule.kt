package com.redraft.pdfedit

import android.content.Context

/**
 * Ensures the native library is loaded and provides the engine instance.
 *
 * The library is loaded lazily, on the first engine request. Calling
 * [initialize] only stores the application context, so a build or device without
 * `libpdfedit.so` still opens the viewer; editing reports itself unavailable.
 */
object PdfEditModule {
    @Volatile private var appContext: Context? = null
    @Volatile private var libraryLoaded = false

    /** True once libpdfedit.so has been successfully loaded. */
    val isLibraryLoaded: Boolean
        get() = libraryLoaded

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun ensureLibraryLoaded() {
        if (!libraryLoaded) {
            synchronized(this) {
                if (!libraryLoaded) {
                    System.loadLibrary("pdfedit")
                    libraryLoaded = true
                }
            }
        }
    }

    fun engine(): PdfEditingEngine {
        val context = appContext
            ?: throw IllegalStateException("PdfEditModule.initialize(context) was not called")
        ensureLibraryLoaded()
        PdfEditingEngineImpl.initialize(context)
        return PdfEditingEngineImpl.get()
    }
}
