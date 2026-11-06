package com.redraft.pdfedit

/** Opaque handle to an open editing document. Owned by JNI; do not inspect. */
data class EditingHandle(val ptr: Long)