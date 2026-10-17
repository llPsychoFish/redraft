plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.redraft.pdfcore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.io.legere.pdfiumandroid)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}