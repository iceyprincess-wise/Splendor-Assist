from pathlib import Path
import re

target = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
text = target.read_text()

marker = "PHASE10_SMARTASSIST_PERSISTENCE_MARKER"

if marker not in text:

    text = text.replace(
        "val config = SmartAssistRepository.configuration()",
        """val repo = SmartAssistRepository(this)
        val config = SmartAssistRepository.configuration()

        // PHASE10_SMARTASSIST_PERSISTENCE_MARKER"""
    )

    text = text.replace(
        "enabled.isChecked = true",
        "enabled.isChecked = config.enabled"
    )

    text = text.replace(
        "repo.updateEnabled(enabled.isChecked)",
        """repo.updateEnabled(enabled.isChecked)

            if (enabled.isChecked) {
                repo.restoreConfiguration()
            }"""
    )

    text = re.sub(
        r'override fun onCreate\(savedInstanceState: Bundle\?\) \{',
        '''override fun onCreate(savedInstanceState: Bundle?) {
''',
        text,
        count=1
    )

    insert = """

    override fun onResume() {
        super.onResume()

        val repo = SmartAssistRepository(this)
        repo.restoreConfiguration()

        findViewById<Switch>(R.id.swEnabled).isChecked =
            SmartAssistRepository.configuration().enabled
    }

"""

    text = text.replace(
        "\n    // PHASE10_CONTROLROOM_RUNTIME_MARKER",
        insert + "\n    // PHASE10_CONTROLROOM_RUNTIME_MARKER"
    )

target.write_text(text)
print("PATCH COMPLETE")
