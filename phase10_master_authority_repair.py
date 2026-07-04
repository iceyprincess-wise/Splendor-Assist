from pathlib import Path
import re

f=Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
src=f.read_text()

# ------------------------------------------------------------
# Locate the broken region
# ------------------------------------------------------------
start=src.index("// PHASE10_ENGINE_STATUS_REFRESH_MARKER")
end=src.index("private fun refreshRuntimeStatus()")

replacement=r'''
    // PHASE10_ENGINE_STATUS_REFRESH_MARKER

    private fun refreshEngineStatus() {

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }

        runCatching {
            RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        }

        runCatching {
            RuntimeVisualizationRegistry.enableVisualization()
        }

        runCatching {
            VisionOverlayRegistry.enableAll()
        }

        runCatching {
            RuntimeOverlayHub.enableDiagnostics()
        }
    }

'''

src=src[:start]+replacement+src[end:]

# ------------------------------------------------------------
# Inject Master Authority inside onCreate()
# ------------------------------------------------------------
anchor='''
        refreshRuntime()
        refreshMetrics()
'''

block=r'''

        // PHASE10_MASTER_AUTHORITY_ACTIVITY_MARKER

        val authoritySlider =
            findViewById<com.google.android.material.slider.Slider>(R.id.authoritySeek)

        val authorityLabel =
            findViewById<TextView>(R.id.tvAuthorityValue)

        authoritySlider.value=config.authority.toFloat()

        authorityLabel.text =
            "${config.authority}% (${config.authority*10} Runtime)"

        authoritySlider.addOnChangeListener { _, value, _ ->

            repo.updateAuthority(value.toInt())

            RuntimePerformanceCoordinator.updateAuthority(
                value.toInt()
            )

            authorityLabel.text =
                "${value.toInt()}% (${value.toInt()*10} Runtime)"

            refreshEngineStatus()
        }

'''

if "PHASE10_MASTER_AUTHORITY_ACTIVITY_MARKER" not in src:
    src=src.replace(anchor,anchor+block,1)

f.write_text(src)

print("PATCH COMPLETE")
