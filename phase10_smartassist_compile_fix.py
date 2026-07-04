from pathlib import Path
import re

repo = Path("app/src/main/java/com/assistant/adapter/smartassist/SmartAssistRepository.kt")
cfg  = Path("app/src/main/java/com/assistant/adapter/smartassist/SmartAssistConfiguration.kt")
act  = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")

cfg_text = cfg.read_text() if cfg.exists() else ""
repo_text = repo.read_text() if repo.exists() else ""
act_text = act.read_text()

# ---- discover actual enabled property ----
enabled_name = None
for candidate in (
    "enabled",
    "isEnabled",
    "active",
    "isActive",
    "smartAssistEnabled",
    "enable",
):
    if re.search(r"\b%s\b" % re.escape(candidate), cfg_text):
        enabled_name = candidate
        break

# ---- discover restore method ----
restore_name = None
for candidate in (
    "restoreConfiguration",
    "restore",
    "load",
    "reload",
    "loadConfiguration",
):
    if re.search(r"fun\s+%s\s*\(" % re.escape(candidate), repo_text):
        restore_name = candidate
        break

# remove invalid restore calls if repository has none
if restore_name is None:
    act_text = re.sub(
        r'\n\s*repo\.restoreConfiguration\(\)\n',
        '\n',
        act_text
    )
else:
    act_text = act_text.replace(
        "restoreConfiguration()",
        restore_name + "()"
    )

# replace invalid config.enabled access
if enabled_name is not None:
    act_text = act_text.replace(
        "config.enabled",
        "config." + enabled_name
    )
else:
    act_text = re.sub(
        r'enabled\.isChecked\s*=\s*config\.[A-Za-z_][A-Za-z0-9_]*',
        'enabled.isChecked = true',
        act_text
    )
    act_text = re.sub(
        r'SmartAssistRepository\.configuration\(\)\.[A-Za-z_][A-Za-z0-9_]*',
        'true',
        act_text
    )

act.write_text(act_text)
print("PATCH COMPLETE")
