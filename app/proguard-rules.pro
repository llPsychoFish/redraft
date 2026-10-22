# R8 rules for Redraft's release build.
#
# PDFium is reached through io.legere:pdfiumandroid, a Kotlin wrapper whose methods are called
# from native code. R8 cannot see those calls, so without these rules the JNI side would resolve
# nothing (or resolve a renamed member) at runtime.

# The binding and its Arrow-free API surface, including nested classes.
-keep class io.legere.pdfiumandroid.** { *; }
-keep interface io.legere.pdfiumandroid.** { *; }

# Any native entry point anywhere in the app: names are resolved by the JVM, never by R8.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Redraft's own JNI-free core still crosses the Kotlin/native boundary through method handles.
-keep class com.redraft.pdfcore.** { *; }

# Kotlin metadata is read reflectively by a few libraries.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn org.jetbrains.annotations.**
