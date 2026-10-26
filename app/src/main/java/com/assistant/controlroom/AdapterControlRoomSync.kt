package com.assistant.controlroom

import android.content.Context
import com.assistant.SmartAssistRepository
import com.assistant.overlay.interceptor.InterceptionRuntimeRegistry
import com.assistant.overlay.repository.GoalkeeperRepository
import com.assistant.overlay.repository.GoalkeeperRuntimeState
import com.assistant.overlay.repository.InterceptionRepository

/**
 * Owns the write side of [AdapterControlRoomRegistry].
 *
 * Before this existed the registry was read-only in practice:
 * [AdapterControlRoomRegistry.setEnabled] and
 * [AdapterControlRoomRegistry.setIntensity] had no callers anywhere in the
 * repository, so every home-screen bulb rendered the static value that
 * [ControlRoomBootstrap] registered at startup. A bulb therefore could not
 * reflect a real control-room change.
 *
 * This sync mirrors authoritative runtime state into the registry so the
 * registry becomes a projection of reality rather than a decorative constant.
 */
object AdapterControlRoomSync {

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        val goalkeeperRepository = GoalkeeperRepository(app)
        val interceptionRepository = InterceptionRepository(app)
        val smartAssistRepository = SmartAssistRepository(app)

        syncAll(goalkeeperRepository, interceptionRepository, smartAssistRepository)
    }

    fun syncAll(
        goalkeeperRepository: GoalkeeperRepository,
        interceptionRepository: InterceptionRepository,
        smartAssistRepository: SmartAssistRepository
    ) {
        val keeper = goalkeeperRepository.state.value
        AdapterControlRoomRegistry.setEnabled("goalkeeper", keeper.enabled)
        AdapterControlRoomRegistry.setIntensity("goalkeeper", keeper.positioning)

        val intercept = interceptionRepository.state.value
        AdapterControlRoomRegistry.setEnabled("interception", intercept.enabled)
        AdapterControlRoomRegistry.setIntensity("interception", intercept.awareness)

        val smart = smartAssistRepository.getCurrentState()
        AdapterControlRoomRegistry.setEnabled("smart_assist", smart.enabled)
        AdapterControlRoomRegistry.setIntensity("smart_assist", smart.configuration.authority)
    }

    /**
     * Projects live runtime state into the registry and returns it. UI surfaces
     * call this so they read current truth instead of the bootstrap snapshot.
     */
    fun snapshot(): List<AdapterControlRoom> {
        AdapterControlRoomRegistry.setEnabled("goalkeeper", GoalkeeperRuntimeState.enabled)
        AdapterControlRoomRegistry.setIntensity("goalkeeper", GoalkeeperRuntimeState.positioning)

        AdapterControlRoomRegistry.setEnabled("interception", InterceptionRuntimeRegistry.enabled)
        AdapterControlRoomRegistry.setIntensity("interception", InterceptionRuntimeRegistry.awareness)

        AdapterControlRoomRegistry.setEnabled("smart_assist", SmartAssistRepository.enabled())
        AdapterControlRoomRegistry.setIntensity(
            "smart_assist",
            SmartAssistRepository.configuration().authority
        )
        return AdapterControlRoomRegistry.getAll()
    }
}
