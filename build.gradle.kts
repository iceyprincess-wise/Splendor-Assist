plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Termux supplies an ARM-host aapt2; ordinary SDK hosts use AGP's supported default.
subprojects {
    val termuxAapt2 = rootProject.findProperty("splendor.termuxAapt2")?.toString()
    if (termuxAapt2 != null && file(termuxAapt2).isFile) {
        extensions.extraProperties.set("android.aapt2FromMavenOverride", termuxAapt2)
    }
}
