#!/bin/sh
# Sourced by gradlew; preserve arguments verbatim and leave other hosts unchanged.
if [ -n "${TERMUX_VERSION:-}" ] || [ "${PREFIX:-}" = /data/data/com.termux/files/usr ] || [ "${PREFIX:-}" = /data/user/0/com.termux/files/usr ]; then
    splendor_explicit_aapt2=
    for splendor_argument in "$@"; do
        case "$splendor_argument" in
            -Pandroid.aapt2FromMavenOverride=*) splendor_explicit_aapt2=${splendor_argument#*=} ;;
        esac
    done
    splendor_selected_aapt2=${splendor_explicit_aapt2:-${SPLENDOR_AAPT2:-}}
    if [ -z "$splendor_selected_aapt2" ]; then
        splendor_configured_aapt2=$(sed -n 's/^splendor\.termuxAapt2=//p' "$APP_HOME/gradle.properties" | tr -d '\r')
        for splendor_candidate in "$splendor_configured_aapt2" "${PREFIX:-}/bin/aapt2" "$HOME/bin/aapt2"; do
            if [ -f "$splendor_candidate" ] && [ -x "$splendor_candidate" ]; then
                splendor_selected_aapt2=$splendor_candidate
                break
            fi
        done
    fi
    if [ ! -f "$splendor_selected_aapt2" ] || [ ! -x "$splendor_selected_aapt2" ]; then
        printf '%s\n' 'FAIL: Termux requires an executable ARM-host aapt2. Set SPLENDOR_AAPT2 to its path or install it in $PREFIX/bin/aapt2.' >&2
        exit 2
    fi
    if [ -z "$splendor_explicit_aapt2" ]; then
        set -- "-Pandroid.aapt2FromMavenOverride=$splendor_selected_aapt2" "$@"
    fi
fi
