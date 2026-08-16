path = "adapter_lag/src/main/java/com/assistant/adapter/lag/LagAdapterService.kt"
with open(path, "r") as f:
    src = f.read()

old_start = (
    "        LoadShedGovernor.start()\n"
    "        ThermalPeekEngine.init(this)"
)
new_start = (
    "        LoadShedGovernor.start()\n"
    "        LoadShedCaptureBrakeEngine.start()\n"
    "        ThermalPeekEngine.init(this)"
)
old_stop = (
    "        LoadShedGovernor.stop()\n"
    "        CpuGovernorEngine.stop()"
)
new_stop = (
    "        LoadShedGovernor.stop()\n"
    "        LoadShedCaptureBrakeEngine.stop()\n"
    "        CpuGovernorEngine.stop()"
)

ok = True
if old_start in src:
    src = src.replace(old_start, new_start, 1)
else:
    print("ERROR: start target not found in LagAdapterService"); ok = False
if old_stop in src:
    src = src.replace(old_stop, new_stop, 1)
else:
    print("ERROR: stop target not found in LagAdapterService"); ok = False
if ok:
    with open(path, "w") as f:
        f.write(src)
    print("OK: LagAdapterService — LoadShedCaptureBrakeEngine wired (start+stop)")
