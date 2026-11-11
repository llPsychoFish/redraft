import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The AAR packages libpdfium.so for every ABI; the native module links against that
// exact file. Extract it before CMake configures so the import path exists.
val pdfiumJniDir = layout.buildDirectory.dir("pdfium-jni")
val pdfiumAar = configurations.detachedConfiguration(
    dependencies.create("io.legere:pdfiumandroid-core:${libs.versions.pdfiumandroid.get()}@aar"),
).apply {
    isTransitive = false
    isCanBeConsumed = false
    isCanBeResolved = true
}

abstract class ExtractPdfiumJniTask : DefaultTask() {
    @get:InputFiles
    abstract val aarFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extractPdfium() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        val copiedAbis = mutableListOf<String>()
        aarFiles.files.filter { it.name.endsWith(".aar") }.forEach { aar ->
            ZipFile(aar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("jni/") || !entry.name.endsWith("/libpdfium.so")) continue
                    val abi = entry.name.split('/')[1]
                    val destination = File(out, "$abi/libpdfium.so")
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                    copiedAbis += abi
                }
            }
        }
        check("arm64-v8a" in copiedAbis) {
            "libpdfium.so for arm64-v8a was not found in ${aarFiles.files}"
        }
    }
}

val extractPdfiumJni = tasks.register<ExtractPdfiumJniTask>("extractPdfiumJni") {
    aarFiles.from(pdfiumAar)
    outputDirectory.set(pdfiumJniDir)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(extractPdfiumJni) }

// Release signing material is NEVER committed (D-28, T-01-01): the keystore path, password and
// alias are read from local.properties or the environment, in that order.
val signingProperties = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        local.inputStream().use { load(it) }
    }
}

fun signingValue(key: String): String? =
    signingProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.redraft"
    ndkVersion = "28.2.13676358"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // TODO(developer, D-27): settle the final applicationId before the first publish. Once
        // published it can never change without shipping a different app. `namespace` may stay
        // `com.redraft` even if the id becomes `com.<name>.redraft`.
        applicationId = "com.rhemnik.redraft"
        minSdk = 26
        targetSdk = 37
        // 0.1.2 is the current release; versionCode must increase with every published build.
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a plus x86_64 for the emulator. 32-bit-only devices cannot install this APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DPDFIUM_JNI_DIR=${pdfiumJniDir.get().asFile.absolutePath}"
            }
        }
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("RELEASE_STORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
            // Only sign when the keystore is actually configured, so a clean clone can still
            // run `assembleRelease` (producing an unsigned APK) without fake credentials.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // The ViewModel logs through android.util.Log; JVM tests need the
            // stub methods to return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/pdfedit/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The AAR already packages libpdfium.so. The native module only imports
            // it for linking, so a second copy must never be packaged.
            pickFirsts += "**/libpdfium.so"
        }
    }
}

dependencies {
    implementation(project(":pdfcore"))
    implementation(libs.io.legere.pdfiumandroid)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.ui.graphics)
    // Compose preview annotations are referenced by main-source composables, so this stays on the
    // implementation classpath; only the preview renderer itself is debug-only.
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
