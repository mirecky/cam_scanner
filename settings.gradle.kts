pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (OCR) sa distribuuje cez JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.adaptech-cz") } }
    }
}

rootProject.name = "Skener"
include(":app")
