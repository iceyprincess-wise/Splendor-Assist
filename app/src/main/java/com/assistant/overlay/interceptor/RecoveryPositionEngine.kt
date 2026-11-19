package com.assistant.overlay.interceptor


object RecoveryPositionEngine {

    // Q65-6 (batch 5b): the flag + state transition are ONE atomic step
    // under the GoalkeeperStateMachine monitor. begin/finish used to do
    // two separate volatile writes (flag, then transition), so a concurrent
    // state() read could briefly observe flag=true/state=IDLE or vice
    // versa. The state machine's transition()/state()/reset() are
    // @Synchronized on the same monitor, and every paired read+transition
    // (OmnipotentGoalkeeperEngine's recovery-restore finally block)
    // synchronizes on it too — so no reader can ever observe a torn pair.
    // The flag itself is NOT derived from the state machine: it is a latch
    // that survives the top-of-evaluate transition to SAVE (deriving it
    // would drop a recovering keeper to IDLE).
    @Volatile
    private var recovering = false

    fun beginRecovery() {
        synchronized(GoalkeeperStateMachine) {
            recovering = true
            GoalkeeperStateMachine.transition(
                GoalkeeperState.RECOVERY
            )
        }
    }

    fun finishRecovery() {
        synchronized(GoalkeeperStateMachine) {
            recovering = false
            GoalkeeperStateMachine.transition(
                GoalkeeperState.IDLE
            )
        }
    }

    fun recovering(): Boolean {
        // Patch 4: a random coin-flip used to drop ~0.05% of true
        // recoveries. Recovery state is reported as-is.
        // Q65-6 (batch 5b): read under the shared monitor so the value
        // can never be observed mid-transition with the state machine.
        return synchronized(GoalkeeperStateMachine) { recovering }
    }
}
