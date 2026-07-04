package com.assistant.overlay.dvr

object DvrSessionCoordinator {

    @Volatile
    private var active = false

    fun beginSession() {
        if (!DvrRuntimeCoordinator.armed()) return
        if (active) return

        active = true
        DvrRuntimeCoordinator.startRecording()
    }

    fun finishSession() {
        if (!active) return

        active = false
        DvrRuntimeCoordinator.saving()
    }

    fun completeSave() {
        DvrRuntimeCoordinator.stop()
    }

    fun active(): Boolean = active
}
