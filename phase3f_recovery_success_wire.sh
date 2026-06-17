#!/data/data/com.termux/files/usr/bin/bash
set -e

FILE="app/src/main/java/com/assistant/recovery/AdapterRecoveryEngine.kt"

perl -0pi -e '
s/private val cooldowns =
        mutableMapOf<String, Long>\(\)/private val cooldowns =
        mutableMapOf<String, Long>()

    private val pendingRecovery =
        mutableSetOf<String>()/s;

s/if \(
                                    status == "OFFLINE"
                                \) \{

                                    offline\+\+

                                    val now =
                                        System\.currentTimeMillis\(\)

                                    val last =
                                        cooldowns\[
                                            snapshot\.adapterName
                                        \] \?: 0L

                                    if \(
                                        now - last >=
                                        COOLDOWN_MS
                                    \) \{

                                        cooldowns\[
                                            snapshot\.adapterName
                                        \] = now

                                        launchAdapter\(
                                            context,
                                            snapshot\.adapterName
                                        \)
                                    \}
                                \}/if (
                                    status == "OFFLINE"
                                ) {

                                    offline++

                                    val now =
                                        System.currentTimeMillis()

                                    val last =
                                        cooldowns[
                                            snapshot.adapterName
                                        ] ?: 0L

                                    if (
                                        now - last >=
                                        COOLDOWN_MS
                                    ) {

                                        cooldowns[
                                            snapshot.adapterName
                                        ] = now

                                        pendingRecovery.add(
                                            snapshot.adapterName
                                        )

                                        launchAdapter(
                                            context,
                                            snapshot.adapterName
                                        )
                                    }
                                } else {

                                    if (
                                        pendingRecovery.contains(
                                            snapshot.adapterName
                                        )
                                    ) {

                                        pendingRecovery.remove(
                                            snapshot.adapterName
                                        )

                                        RecoveryMetricsRegistry
                                            .recordSuccess()

                                        RuntimeLogger.log(
                                            "Recovery verified :: ${snapshot.adapterName}",
                                            "RECOVERY"
                                        )
                                    }
                                }/s;
' "$FILE"

echo
echo "PHASE3F SUCCESS VERIFICATION WIRED"
