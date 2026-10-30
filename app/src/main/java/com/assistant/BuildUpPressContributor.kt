object BuildUpPressContributor : GameplayContributor {
    override val engineName   = "BuildUpPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    
    private val nativeBuffer = FloatArray(4)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!NativeBridge.nativeLoaded) return null
        
        val ownership = try { Phase3WorldStateStore.current().ownership } catch(_:Throwable) { com.assistant.BallOwnershipResult(false) }
        val owner = ownership.owner
        
        NativeBridge.nativeBuildUpPressCompute(
            frame.hasBall,
            frame.trusted,
            frame.confidence,
            ownership.hasOwner,
            owner?.x ?: 0f,
            owner?.y ?: 0f,
            owner?.isUserTeam ?: false,
            frame.ballX,
            frame.ballY,
            nativeBuffer
        )
        
        if (nativeBuffer[0] <= 0.5f) return null
        
        return EngineContribution(engineName, ActionClass.DEFEND,
            nativeBuffer[1], nativeBuffer[2], nativeBuffer[3], frame.confidence, 20L)
    }
}
/* ======
BuildUpPressContributor Anchor
====== */

/* ========
CrossContributor
======== */
