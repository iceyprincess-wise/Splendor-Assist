from pathlib import Path
import re

repo = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/SmartAssistRepository.kt")
rpc  = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt")

r = repo.read_text()
p = rpc.read_text()

# ------------------------------------------------------------------
# SmartAssistConfiguration.authority
# ------------------------------------------------------------------
if "val authority:" not in r:
    r = r.replace(
        "val panicThreshold: Int = 80",
        "val panicThreshold: Int = 80,\n    val authority: Int = 100"
    )

# ------------------------------------------------------------------
# SharedPreferences key
# ------------------------------------------------------------------
if 'KEY_AUTHORITY' not in r:
    r = r.replace(
        'private const val KEY_PANIC_THRESHOLD = "panic_threshold"',
        'private const val KEY_PANIC_THRESHOLD = "panic_threshold"\n'
        '        private const val KEY_AUTHORITY = "authority"'
    )

# ------------------------------------------------------------------
# loadState()
# ------------------------------------------------------------------
r = r.replace(
    "panicThreshold = prefs.getInt(KEY_PANIC_THRESHOLD, 80)",
    "panicThreshold = prefs.getInt(KEY_PANIC_THRESHOLD, 80),\n"
    "                authority = prefs.getInt(KEY_AUTHORITY,100)"
)

# ------------------------------------------------------------------
# saveState()
# ------------------------------------------------------------------
if "putInt(KEY_AUTHORITY" not in r:
    r = r.replace(
        "putInt(KEY_PANIC_THRESHOLD, newState.configuration.panicThreshold)",
        "putInt(KEY_PANIC_THRESHOLD, newState.configuration.panicThreshold)\n"
        "            putInt(KEY_AUTHORITY, newState.configuration.authority)"
    )

# ------------------------------------------------------------------
# updateAuthority()
# ------------------------------------------------------------------
if "fun updateAuthority(" not in r:
    anchor = "fun getCurrentState(): SmartAssistState = _state.value"
    r = r.replace(
        anchor,
        """
    fun updateAuthority(authority:Int)=
        saveState(
            _state.value.copy(
                configuration =
                    _state.value.configuration.copy(
                        authority = authority
                    )
            )
        )

""" + anchor
    )

repo.write_text(r)

# ------------------------------------------------------------------
# RuntimePerformanceCoordinator.updateAuthority()
# ------------------------------------------------------------------
if "fun updateAuthority(" not in p:
    insert = """

    private var masterAuthority:Int = 100

    fun updateAuthority(authority:Int){
        masterAuthority = authority.coerceIn(0,100)
    }

    fun authority():Int = masterAuthority

"""
    p = re.sub(
        r'object\s+RuntimePerformanceCoordinator\s*\{',
        lambda m: m.group(0)+insert,
        p,
        count=1
    )

rpc.write_text(p)

print("MASTER AUTHORITY PATCH COMPLETE")
