class SmartAssistControlRoomBridge(
    private val repository: SmartAssistRepository
) {
    
    val state: StateFlow<SmartAssistState> = repository.state
    
    fun updateEnabled(enabled: Boolean) {
        repository.updateEnabled(enabled)
    }
    
    fun updatePanicMode(panic: Boolean) {
        repository.updatePanicMode(panic)
    }

    fun updateAuthority(authority:Int){
        repository.updateAuthority(authority)
        RuntimePerformanceCoordinator.updateAuthority(authority)
    }

fun updateThresholds(pass: Int, shot: Int, cross: Int) {
        repository.updateThresholds(pass, shot, cross)
    }
    
    fun saveCurrentSettings(): Boolean {
        // Real save: persist the current in-memory configuration through the
        // repository. Previously this returned true unconditionally while
        // performing no write, so callers were told a save succeeded when
        // nothing was saved.
        return try {
            repository.saveState(repository.getCurrentState())
            true
        } catch (e: Exception) {
            com.assistant.diagnostic.RuntimeLogger.log(
                "saveCurrentSettings failed: " + e.message, "SMART_ASSIST")
            false
        }
    }
    
    fun getCurrentSettings(): SmartAssistState {
        return repository.getCurrentState()
    }
}
/* ======
SmartAssistControlRoomBridge Anchor
====== */

/* ========
SmartAssistRepository
======== */
