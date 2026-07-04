from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
src = f.read_text()

# Remove the previously injected invalid block completely.
src = re.sub(
    r'\n\s*// PHASE10_MASTER_AUTHORITY_ACTIVITY_MARKER.*?refreshEngineStatus\(\)',
    '\n        refreshEngineStatus()',
    src,
    flags=re.S
)

# Inject ONLY inside onCreate(), immediately after refreshEngineStatus().
block = r'''
        // PHASE10_MASTER_AUTHORITY_ACTIVITY_MARKER

        val authoritySlider =
            findViewById<com.google.android.material.slider.Slider>(R.id.authoritySeek)

        val authorityLabel =
            findViewById<android.widget.TextView>(R.id.tvAuthorityValue)

        authoritySlider.value = config.authority.toFloat()
        authorityLabel.text =
            "${config.authority}% (${config.authority * 10} Runtime)"

        authoritySlider.addOnChangeListener { _, value, _ ->

            repo.updateAuthority(value.toInt())
            RuntimePerformanceCoordinator.updateAuthority(value.toInt())

            authorityLabel.text =
                "${value.toInt()}% (${value.toInt() * 10} Runtime)"
        }

        refreshEngineStatus()
'''

src = src.replace(
    "refreshEngineStatus()",
    block,
    1
)

f.write_text(src)
print("PATCH COMPLETE")
