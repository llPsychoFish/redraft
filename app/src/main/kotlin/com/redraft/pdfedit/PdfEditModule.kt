package com.redraft.pdfedit

import android.content.Context

/** Ensures the native library is loaded and provides the engine instance. */
object PdfEditModule {
    @Volatile private var _libraryLoaded = false
    
    private fun ensureLibraryLoaded() {
        if (!_libraryLoaded) {
            synchronized(this) {
                if (!_libraryLoaded) {
                    System.loadLibrary("pdfedit")
                    _libraryLoaded = true
                }
            }
        }
    }
    
    fun initialize(context: Context) {
        ensureLibraryLoaded()
        PdfEditingEngineImpl.initialize(context)
    }

    fun engine(): PdfEditingEngine {
        ensureLibraryLoaded()
        return PdfEditingEngineImpl.get()
    }
}