path = "adapter_memory/src/main/java/com/assistant/adapter/memory/MemoryAdapterService.kt"
with open(path, "r") as f:
    src = f.read()
old = (
    "                MemoryPressureBusEngine.publish(tier.name, availableMb)\n"
    "                publishHealth("
)
new = (
    "                MemoryPressureBusEngine.publish(tier.name, availableMb)\n"
    "                MemoryCaptureGateEngine.onTierChange(tier.name, availableMb)\n"
    "                publishHealth("
)
if old in src:
    src = src.replace(old, new, 1)
    with open(path, "w") as f:
        f.write(src)
    print("OK: MemoryAdapterService — MemoryCaptureGateEngine wired")
else:
    print("ERROR: target not found in MemoryAdapterService")
