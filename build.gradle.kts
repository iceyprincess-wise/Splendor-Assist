// Root build file — intentionally minimal (No53-D1).
// - Plugin VERSIONS live here and only here: AGP 8.2.2 and Kotlin 1.9.22.
//   Both modules apply their plugins WITHOUT versions and inherit these.
// - REPOSITORIES live in settings.gradle.kts (pluginManagement +
//   dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS). Do NOT add a
//   repositories {} block here: project repos are forbidden by that mode
//   and the build would fail.
// - `apply false` is correct: the plugins are applied in app/build.gradle.kts
//   (application) and core/build.gradle.kts (library). Nothing else belongs
//   here: there is no buildscript block, no clean task (Gradle provides it),
//   and no subproject configuration.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
