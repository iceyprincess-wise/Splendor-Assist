#!/data/data/com.termux/files/usr/bin/bash
set -e

FILE="app/src/main/java/com/assistant/recovery/AdapterRecoveryEngine.kt"

grep -q "activeLaunches" "$FILE" && {
    echo "PHASE3G ALREADY APPLIED"
    exit 0
}

perl -0pi -e '
s/private val pendingRecovery =
        mutableSetOf<String>\(\)/private val pendingRecovery =
        mutableSetOf<String>()

    private val activeLaunches =
        mutableSetOf<String>()/s;

s/private fun launchAdapter\(
        context: Context,
        adapterName: String
    \) \{/private fun launchAdapter(
        context: Context,
        adapterName: String
    ) {

        synchronized(activeLaunches) {

            if (
                activeLaunches.contains(
                    adapterName
                )
            ) {
                return
            }

            activeLaunches.add(
                adapterName
            )
        }/s;

s/RuntimeLogger\.log\(
                "Recovery failed :: \$adapterName :: \$\{e\.javaClass\.simpleName\}",
                "RECOVERY"
            \)/RuntimeLogger.log(
                "Recovery failed :: \$adapterName :: \${e.javaClass.simpleName}",
                "RECOVERY"
            )

            synchronized(activeLaunches) {
                activeLaunches.remove(
                    adapterName
                )
            }/s;

s/RecoveryMetricsRegistry
                                            \.recordSuccess\(\)

                                        RuntimeLogger\.log\(
                                            "Recovery verified :: ",
                                            "RECOVERY"
                                        \)/RecoveryMetricsRegistry
                                            .recordSuccess()

                                        synchronized(activeLaunches) {
                                            activeLaunches.remove(
                                                snapshot.adapterName
                                            )
                                        }

                                        RuntimeLogger.log(
                                            "Recovery verified :: ${snapshot.adapterName}",
                                            "RECOVERY"
                                        )/s;
' "$FILE"

echo
echo "PHASE3G LAUNCH GUARD WIRED"
