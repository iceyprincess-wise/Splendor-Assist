from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist" / "adapter_smartassist" / "src" / "main" / "java" / "com" / "assistant" / "adapter" / "smartassist"

rpc = ROOT / "RuntimePerformanceCoordinator.kt"
agc = ROOT / "ActiveGestureController.kt"
vc = ROOT / "VisionCore.kt"

required = [rpc, agc, vc]
missing = [str(p) for p in required if not p.exists()]
if missing:
    print("PATCH ABORTED")
    print("Missing:")
    for m in missing:
        print(m)
    raise SystemExit(1)

rpc_text = rpc.read_text()

if "PHASE9_RUNTIME_PERFORMANCE_ORCHESTRATION_MARKER" not in rpc_text:

    insert = '''

    // PHASE9_RUNTIME_PERFORMANCE_ORCHESTRATION_MARKER

    fun synchronizeRuntimePipeline() {

        RuntimeDiagnosticsRegistry.refresh()
        RuntimeVisualizationRegistry.refresh()

        FPSMonitor.refresh()
        VisionLatencyMonitor.refresh()
        ConfidenceHeatmap.refresh()

        refresh()
    }
'''

    rpc_text = rpc_text.replace(
        "\n}",
        insert + "\n}"
    )

    rpc.write_text(rpc_text)

agc_text = agc.read_text()

if "RuntimePerformanceCoordinator.synchronizeRuntimePipeline()" not in agc_text:

    marker = "PHASE9_RUNTIME_ACTIVATION_MARKER"

    if marker in agc_text:
        agc_text = agc_text.replace(
            marker,
            "RuntimePerformanceCoordinator.synchronizeRuntimePipeline()\n\n        " + marker,
            1
        )
    else:
        agc_text += "\n\nRuntimePerformanceCoordinator.synchronizeRuntimePipeline()\n"

    agc.write_text(agc_text)

vc_text = vc.read_text()

if "RuntimePerformanceCoordinator.synchronizeRuntimePipeline()" not in vc_text:

    marker = "PHASE9_RUNTIME_ACTIVATION_MARKER"

    if marker in vc_text:
        vc_text = vc_text.replace(
            marker,
            "RuntimePerformanceCoordinator.synchronizeRuntimePipeline()\n\n " + marker,
            1
        )
    else:
        vc_text += "\n\nRuntimePerformanceCoordinator.synchronizeRuntimePipeline()\n"

    vc.write_text(vc_text)

print("PATCH COMPLETE")
