from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

files = {
"BallOverlay.kt": """package com.assistant.adapter.smartassist

data class BallOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().ballOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object BallOverlay {

    @Volatile
    private var state = BallOverlayState()

    fun current():BallOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = BallOverlayState(
            enabled =
                VisionConfigurationEngine.current().ballOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(ballOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(ballOverlayEnabled = false)
        }
        refresh()
    }
}
""",

"PlayerOverlay.kt": """package com.assistant.adapter.smartassist

data class PlayerOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().playerOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object PlayerOverlay {

    @Volatile
    private var state = PlayerOverlayState()

    fun current():PlayerOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = PlayerOverlayState(
            enabled =
                VisionConfigurationEngine.current().playerOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(playerOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(playerOverlayEnabled = false)
        }
        refresh()
    }
}
""",

"GoalOverlay.kt": """package com.assistant.adapter.smartassist

data class GoalOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().goalOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object GoalOverlay {

    @Volatile
    private var state = GoalOverlayState()

    fun current():GoalOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = GoalOverlayState(
            enabled =
                VisionConfigurationEngine.current().goalOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(goalOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(goalOverlayEnabled = false)
        }
        refresh()
    }
}
"""
}

for name, content in files.items():
    (PKG / name).write_text(content, encoding="utf-8")
    print("CREATED:", name)
