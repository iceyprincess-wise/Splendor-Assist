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
    }
}

rootProject.name = "SplendorAssistEngine"
include(":app")
// INJECTED KERNEL EXTENSIONS
include(":adapter_lmk")
include(":adapter_sync")
include(":adapter_input")
include(":adapter_net")
include(":diagnostic_core")
