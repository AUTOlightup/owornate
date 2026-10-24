#!/system/bin/sh
# Fixed payload and actions only; invoked by the authorized manager.
#   status      host only: available / installed / ready (read-only)
#   check       host + boot whitelist: present / missing / unavailable (read-only)
#   keepalive   add this app to the boot whitelist once (explicit write)
#   repair      keepalive + host, no Launcher reload (explicit repair)
#   breeno-hide negative-one-screen hides the Breeno row (writes Settings.System)
#   breeno-show restore the Breeno row
#   install     replace the shared Hook with v13, reload Launcher once, then keepalive
#   capture-status / capture-install: capture buttons, no whitelist write
set -eu
BASE=/data/local/tmp/watchhide
SOURCE=/data/user/0/dev.codex.watchworkshop/files/widget-host
LAUNCHER=com.heytap.wearable.launcher
PKG=dev.codex.watchworkshop
SYS=/data/system/sys_process_switch_list.xml
V9=6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722
V10=0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d
V11=9b8116a64219e919e0f76777f9bbefb4f42821e651a337d109a21a3a457823f1
V12=4bc93055211b1c070155b45808955b6e41380c51edc63010ba8f0f759c508ac0
V13=d0d3406f1ce940f20f2089b2962c41d5eb0830249cd781887f37fecc90c088d7
THEME=706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74
V13_INITIAL=aff84e9105a66738234398db5e5beef4fd67b384a804308479cfda472ef349a1
V13_CODE21=fdbf56b324eb29fd37f9d92644b051194503024ea34fcca4b1ddcdd9ecb3d46a
V13_CODE22=46b4ae970215c889e36524c3969b66e45bba45100afd973daae07947f531ea19
fail() { echo "$1"; exit 1; }
sha() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }

# WatchHide writes injected.marker after a successful injection ("pid=<pid> ...").
# A marker for the live Launcher PID and not older than hook.js proves this Hook is live.
loaded() {
    [ -n "$1" ] || return 1
    m=$(cat "$BASE/injected.marker" 2>/dev/null || true)
    case "$m" in "pid=$1 "*) ;; *) return 1;; esac
    [ "$(stat -c %Y "$BASE/injected.marker" 2>/dev/null || echo 0)" -ge "$(stat -c %Y "$BASE/hook.js" 2>/dev/null || echo 2147483647)" ]
}

host_status() {
    if [ "$kind" != "$V11" ] && [ "$kind" != "$V12" ] && [ "$kind" != "$V13" ]; then echo available; return 0; fi
    p=$(pidof "$LAUNCHER" 2>/dev/null || true)
    case "$p" in ''|*[!0-9]*) echo installed; return 0;; esac
    if loaded "$p"; then echo ready; else echo installed; fi
}

# Read-only: no backup, no temp file, no whitelist write.
keep_state() {
    f="$SYS"
    [ -f "$f" ] && [ -r "$f" ] || { echo unavailable; return 0; }
    grep -q '<process_switch_list>' "$f" 2>/dev/null &&
        grep -q '</process_switch_list>' "$f" 2>/dev/null ||
        { echo unavailable; return 0; }
    if grep -Fq "<allow_process>$PKG</allow_process>" "$f" 2>/dev/null; then
        echo present
    else
        echo missing
    fi
}

# Explicit write. Keep every original entry and the file's mode/owner; back up once.
# Android reads this list only at boot, so a new entry needs one watch restart.
keepalive() {
    f="$SYS"
    [ -f "$f" ] && [ -r "$f" ] || { echo unavailable; return 0; }
    if grep -Fq "<allow_process>$PKG</allow_process>" "$f" 2>/dev/null; then
        echo present
        return 0
    fi
    grep -q '<process_switch_list>' "$f" 2>/dev/null &&
        grep -q '</process_switch_list>' "$f" 2>/dev/null ||
        { echo unavailable; return 0; }
    backup="$BASE/sys_process_switch_list.before-owornate.xml"
    if [ ! -e "$backup" ]; then
        cp -p "$f" "$backup" 2>/dev/null || { echo unavailable; return 0; }
    fi
    tmp="$BASE/sys_process_switch_list.owornate-new"
    before=$(grep -c '<allow_process>' "$f" 2>/dev/null || true)
    grep -v '</process_switch_list>' "$f" 2>/dev/null >"$tmp" || {
        rm -f "$tmp"; echo unavailable; return 0
    }
    printf '  <allow_process>%s</allow_process>\n</process_switch_list>\n' "$PKG" >>"$tmp" 2>/dev/null || {
        rm -f "$tmp"; echo unavailable; return 0
    }
    after=$(grep -c '<allow_process>' "$tmp" 2>/dev/null || true)
    [ "$after" = "$((before + 1))" ] || { rm -f "$tmp"; echo unavailable; return 0; }
    cat "$tmp" >"$f" 2>/dev/null || { rm -f "$tmp"; echo unavailable; return 0; }
    rm -f "$tmp"
    grep -Fq "<allow_process>$PKG</allow_process>" "$f" 2>/dev/null || {
        echo unavailable; return 0
    }
    echo added
}

install_shared() {
    capture_only=${1:-0}
    [ "$(sha "$SOURCE/WatchHide-Launcher-Hook-v13.js")" = "$V13" ] || fail PAYLOAD_INVALID
    sh "$BASE/watchhide-bootstrap.sh" status | grep -q '^supervisor_pid=[0-9][0-9]*$' || fail SUPERVISOR_REQUIRED
    p=$(pidof "$LAUNCHER" 2>/dev/null || true)
    case "$p" in ''|*[!0-9]*) fail LAUNCHER_UNAVAILABLE;; esac
    actual=$(tr '\000' ' ' <"/proc/$p/cmdline")
    set -- $actual
    [ "$#" = 1 ] && [ "$1" = "$LAUNCHER" ] || fail LAUNCHER_IDENTITY
    if [ "$(sha "$BASE/hook.js")" != "$V13" ]; then
        backup="$BASE/hook-before-v13.js"
        if [ ! -e "$backup" ]; then cp -p "$BASE/hook.js" "$backup"; fi
        cp "$SOURCE/WatchHide-Launcher-Hook-v13.js" "$BASE/hook.js.widgets-new"
        [ "$(sha "$BASE/hook.js.widgets-new")" = "$V13" ] || {
            rm -f "$BASE/hook.js.widgets-new"; fail PAYLOAD_INVALID
        }
        chmod 644 "$BASE/hook.js.widgets-new"
        chown 0:0 "$BASE/hook.js.widgets-new"
        mv "$BASE/hook.js.widgets-new" "$BASE/hook.js"
    fi
    kill -9 "$p" || fail RESTART_FAILED
    # The background Launcher is not restarted until HOME is requested on A.92.
    am start -a android.intent.action.MAIN -c android.intent.category.HOME -p "$LAUNCHER" >/dev/null 2>&1 || true
    n=0
    while [ "$n" -lt 15 ]; do
        sleep 1
        now=$(pidof "$LAUNCHER" 2>/dev/null || true)
        if [ -n "$now" ] && [ "$now" != "$p" ] && loaded "$now"; then
            if [ "$capture_only" = 1 ]; then
                settings put system codex_capture_enabled 1 || fail CAPTURE_SETTING_FAILED
                keep=$(keep_state)
            else keep=$(keepalive); fi
            case "$keep" in present|added|missing|unavailable) ;; *) keep=unavailable;; esac
            echo ready
            echo "$keep"
            exit 0
        fi
        n=$((n + 1))
    done
    fail START_UNCONFIRMED
}

[ "$(id -u)" = 0 ] || fail ROOT_REQUIRED
[ "$(getprop ro.product.model)" = OW20W1 ] && [ "$(getprop ro.build.display.id)" = OW20W1_11_A.92 ] || fail DEVICE_UNSUPPORTED
# Fresh/standalone users use the APK-owned runtime. Existing shared installations
# keep their established supervisor and Hook; never inject a second runtime.
if [ ! -f "$BASE/hook.js" ]; then
    RUNTIME=/data/user/0/dev.codex.watchworkshop/files/monet/runtime-manage.sh
    [ -r "$RUNTIME" ] || fail PAYLOAD_INVALID
    BASE=/data/local/tmp/watchworkshop-runtime
    own_action() {
        result=$(sh "$RUNTIME" "$1" "$2") || fail "$result"
    }
    own_status() { sh "$RUNTIME" status "$1"; }
    case "${1:-}" in
      runtime-kind) sh "$RUNTIME" kind; exit $?;;
      status) own_status widgets; exit $?;;
      check) own_status widgets; keep_state; echo own; exit 0;;
      capture-status) own_status capture; exit $?;;
      capture-check) own_status capture; echo own; exit 0;;
      install)
        own_action install widgets
        own_action enable widgets
        own_status widgets
        keepalive
        echo own; exit 0;;
      enable|disable|uninstall|update)
        own_action "$1" widgets
        own_status widgets
        keep_state
        echo own; exit 0;;
      capture-install)
        own_action install capture
        own_action enable capture
        own_status capture
        keep_state; exit 0;;
      capture-enable|capture-disable|capture-uninstall|capture-update)
        own_action "${1#capture-}" capture
        own_status capture; exit 0;;
      keepalive|repair)
        r=$(keepalive); echo "$r"
        [ "$r" != unavailable ] || fail KEEP_UNAVAILABLE
        exit 0;;
      breeno-hide|breeno-show)
        state=$(own_status widgets) || fail "$state"
        case "$state" in ready|installed|disabled|update) ;; *) fail MODULE_NOT_INSTALLED;; esac
        value=0; [ "$1" != breeno-hide ] || value=1
        settings put system codex_qc_hide_breeno "$value" || fail SETTING_FAILED
        [ "$value" = 0 ] && echo shown || echo hidden; exit 0;;
      *) fail ACTION_INVALID;;
    esac
fi
kind=$(sha "$BASE/hook.js")
# Recognize deployed v13 revisions, but compare the actual bytes when upgrading.
case "$kind" in "$V13_INITIAL"|"$V13_CODE21"|"$V13_CODE22") kind=$V13;; esac
case "$kind" in "$V9"|"$V10"|"$V11"|"$V12"|"$V13") ;; *) fail SHARED_REQUIRED;; esac
[ "$(sha /data/local/tmp/watchface-theme/theme.jar)" = "$THEME" ] || fail SHARED_REQUIRED
case "${1:-}" in
runtime-kind) echo shared; exit 0;;
uninstall|capture-uninstall) fail SHARED_RUNTIME;;
update|capture-update) install_shared 1;;
status) host_status; exit 0;;
capture-status|capture-check)
    if [ "$kind" != "$V12" ] && [ "$kind" != "$V13" ]; then echo available
    elif [ "$(host_status)" != ready ]; then echo installed
    elif [ "$(settings get system codex_capture_enabled)" = 1 ]; then echo ready
    else echo disabled; fi
    [ "$1" != capture-check ] || echo shared
    exit 0;;
capture-install) install_shared 1;;
capture-enable)
    { [ "$kind" = "$V12" ] || [ "$kind" = "$V13" ]; } && [ "$(host_status)" = ready ] || fail START_UNCONFIRMED
    settings put system codex_capture_enabled 1 || fail CAPTURE_SETTING_FAILED
    echo enabled; exit 0;;
capture-disable)
    { [ "$kind" = "$V12" ] || [ "$kind" = "$V13" ]; } || fail SHARED_REQUIRED
    settings put system codex_capture_enabled 0 || fail CAPTURE_SETTING_FAILED
    echo disabled; exit 0;;
check)
    host_status
    keep_state
    exit 0;;
keepalive)
    r=$(keepalive)
    echo "$r"
    [ "$r" != unavailable ] || exit 1
    exit 0;;
repair)
    r=$(keepalive)
    [ "$r" != unavailable ] || fail KEEP_UNAVAILABLE
    host_status
    echo "$r"
    exit 0;;
breeno-hide)
    { [ "$kind" = "$V11" ] || [ "$kind" = "$V12" ] || [ "$kind" = "$V13" ]; } || fail SHARED_REQUIRED
    settings put system codex_qc_hide_breeno 1
    echo hidden; exit 0;;
breeno-show)
    { [ "$kind" = "$V11" ] || [ "$kind" = "$V12" ] || [ "$kind" = "$V13" ]; } || fail SHARED_REQUIRED
    settings put system codex_qc_hide_breeno 0
    echo shown; exit 0;;
install) install_shared;;
*) fail ACTION_INVALID;;
esac

