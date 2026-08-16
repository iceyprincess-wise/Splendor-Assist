import re

# 1. Wire OomAdaptiveThrottleEngine and GestureTimingFeedbackEngine in InputAdapterService
path = "adapter_input/src/main/java/com/assistant/adapter/input/InputAdapterService.kt"
with open(path, "r") as f:
    src = f.read()

old_start = '''        InputPriorityEngine.start()
        InputLatencyEngine.start()
        TouchQualityEngine.start()
        RuntimeLogger.log("Input engine stack ignited: 3 engines [LATENCY+QUALITY+PRIORITY]", "INPUT")'''

new_start = '''        InputPriorityEngine.start()
        InputLatencyEngine.start()
        TouchQualityEngine.start()
        OomAdaptiveThrottleEngine.start()
        GestureTimingFeedbackEngine.reset()
        RuntimeLogger.log("Input engine stack ignited: 5 engines [LATENCY+QUALITY+PRIORITY+OOM+GESTURE_TIMING]", "INPUT>

src = src.replace(old_start, new_start)

old_stop = '''        InputLatencyEngine.stop()
        TouchQualityEngine.stop()
        InputPriorityEngine.stop()'''

new_stop = '''        InputLatencyEngine.stop()
        TouchQualityEngine.stop()
        InputPriorityEngine.stop()
        OomAdaptiveThrottleEngine.stop()
        GestureTimingFeedbackEngine.reset()'''

src = src.replace(old_stop, new_stop)
with open(path, "w") as f:
    f.write(src)
print("InputAdapterService: 5 engines wired")

# 2. Wire MemoryCaptureGateEngine in MemoryAdapterService (call onTierChange after publish)
path = "adapter_memory/src/main/java/com/assistant/adapter/memory/MemoryAdapterService.kt"
with open(path, "r") as f:
    src = f.read()

old_pub = '''                MemoryPressureBusEngine.publish(tier.name, availableMb)'''
new_pub = '''                MemoryPressureBusEngine.publish(tier.name, availableMb)
                MemoryCaptureGateEngine.onTierChange(tier.name, availableMb)''
src = src.replace(old_pub, new_pub)
with open(path, "w") as f:
    f.write(src)
print("MemoryAdapterService: MemoryCaptureGateEngine wired")

# 3. Wire LoadShedCaptureBrakeEngine in LagAdapterService
path = "adapter_lag/src/main/java/com/assistant/adapter/lag/LagAdapterService.kt"
with open(path, "r") as f:
    src = f.read()

# Find LoadShedGovernor.start() and add brake after it
old_loadshed = 'LoadShedGovernor.start()'
new_loadshed = 'LoadShedGovernor.start()\n        LoadShedCaptureBrakeEngine.start()'
src = src.replace(old_loadshed, new_loadshed, 1)  # first occurrence only

old_loadshed_stop = 'LoadShedGovernor.stop()'
new_loadshed_stop = 'LoadShedGovernor.stop()\n        LoadShedCaptureBrakeEngine.stop()'
src = src.replace(old_loadshed_stop, new_loadshed_stop, 1)

with open(path, "w") as f:
    f.write(src)
print("LagAdapterService: LoadShedCaptureBrakeEngine wired")
