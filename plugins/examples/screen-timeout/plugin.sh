#!/system/bin/sh
# One-shot shell-v1 example. The manager serializes calls and supplies the data directory.
set -eu
: "${OW_PLUGIN_DATA:?missing data directory}"
: "${OW_SETTING_timeout:?missing timeout}"
case "$OW_SETTING_timeout" in 15000|30000|60000) ;; *) echo "invalid timeout" >&2; exit 1;; esac
state="$OW_PLUGIN_DATA/timeout-state"
numeric() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
current() { settings get system screen_off_timeout; }
load_state() {
    original=$(sed -n '1p' "$state")
    applied=$(sed -n '2p' "$state")
    case "$original" in null) ;; *) numeric "$original" || exit 1;; esac
    numeric "$applied" || exit 1
}
status() {
    if [ ! -f "$state" ]; then
        echo "OWORNATE_PLUGIN_V1 disabled"
    else
        load_state
        if [ "$(current)" != "$applied" ]; then
            echo "screen timeout changed outside plugin; uninstall to release ownership" >&2
            exit 1
        fi
        echo "OWORNATE_PLUGIN_V1 enabled"
    fi
}
apply() {
    if [ -f "$state" ]; then
        load_state
        [ "$(current)" = "$applied" ] || { echo "screen timeout changed outside plugin" >&2; exit 1; }
    else
        original=$(current)
        case "$original" in null) ;; *) numeric "$original" || exit 1;; esac
    fi
    # Record recovery information before the system write.
    printf '%s\n%s\n' "$original" "$OW_SETTING_timeout" > "$state.next"
    mv "$state.next" "$state"
    settings put system screen_off_timeout "$OW_SETTING_timeout"
    status
}
disable() {
    if [ -f "$state" ]; then
        load_state
        # Do not overwrite a later system/user change.
        if [ "$(current)" = "$applied" ]; then
            if [ "$original" = null ]; then settings delete system screen_off_timeout >/dev/null
            else settings put system screen_off_timeout "$original"
            fi
            [ "$(current)" = "$original" ] || { echo "restore failed" >&2; exit 1; }
        fi
        rm "$state"
    fi
    status
}
case "${1-}" in
    status) status ;;
    enable) apply ;;
    disable|uninstall) disable ;;
    configure) if [ -f "$state" ]; then apply; else status; fi ;;
    *) echo "unsupported action" >&2; exit 1 ;;
esac
