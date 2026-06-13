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

include(":adapter_ping")
include(":adapter_stutter")
include(":adapter_lag")
include(":adapter_boot")
include(":adapter_watchdog")

include(":adapter_memory")
include(":adapter_thermal")
include(":adapter_battery")
include(":adapter_scheduler")
include(":adapter_smartassist")

include(":adapter_interruption")
