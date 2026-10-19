// Workaround for AGP 9.x AndroidLocationsException when both ANDROID_PREFS_ROOT and ANDROID_USER_HOME are set
if (System.getenv("ANDROID_PREFS_ROOT") != null && System.getenv("ANDROID_USER_HOME") != null) {
    try {
        val pe = Class.forName("java.lang.ProcessEnvironment")
        val field = pe.getDeclaredField("theCaseInsensitiveEnvironment").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as? MutableMap<String, String>)?.remove("ANDROID_PREFS_ROOT")
    } catch (_: Throwable) {}
    try {
        val env = System.getenv()
        val field = env.javaClass.getDeclaredField("m").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(env) as? MutableMap<String, String>)?.remove("ANDROID_PREFS_ROOT")
    } catch (_: Throwable) {}
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Redraft"
include(":app")
include(":pdfcore")
