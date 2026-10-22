import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // TODO(developer, D-27): settle the final applicationId before the first publish. Once
        // published it can never change without shipping a different app. `namespace` may stay
        // `com.redraft` even if the id becomes `com.<name>.redraft`.
        applicationId = "com.redraft"
        minSdk = 26
        targetSdk = 37
        // 0.1.0 is the first release; versionCode must increase with every published build.
        versionCode = 2
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a plus x86_64 for the emulator. 32-bit-only devices cannot install this APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
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
}

dependencies {
    implementation(project(":pdfcore"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
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
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
