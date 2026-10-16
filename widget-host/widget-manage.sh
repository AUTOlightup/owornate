#!/system/bin/sh
# Fixed payload and actions only; invoked by the authorized manager.
#   status      接入状态：available / installed / ready（只读）
#   check       status 一行，已接入时再加一行系统进程白名单结果：present / added / unavailable
#   keepalive   只做白名单那一步
#   breeno-hide 负一屏隐藏小布助手一行（写系统设置，hook 下次打开读取生效）
#   breeno-show 负一屏恢复显示小布助手一行
#   install     换成 v11 并重新加载表盘
set -eu
BASE=/data/local/tmp/watchhide
SOURCE=/data/user/0/dev.codex.watchworkshop/files/widget-host
LAUNCHER=com.heytap.wearable.launcher
V9=6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722
V10=0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d
V11=9b8116a64219e919e0f76777f9bbefb4f42821e651a337d109a21a3a457823f1
THEME=706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74
fail() { echo "$1"; exit 1; }
sha() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }

# WatchHide 引导每次注入成功后写 injected.marker（"pid=<pid> mode=... at=..."）。
# 标记里的 pid 就是当前 Launcher、且标记不早于 hook.js，说明正在运行的正是这份 hook。
# 读两个文件，几毫秒；不翻 logcat（这台表的日志十分钟左右就被冲掉）。
loaded() {
    [ -n "$1" ] || return 1
    m=$(cat "$BASE/injected.marker" 2>/dev/null || true)
    case "$m" in "pid=$1 "*) ;; *) return 1;; esac
    [ "$(stat -c %Y "$BASE/injected.marker" 2>/dev/null || echo 0)" -ge "$(stat -c %Y "$BASE/hook.js" 2>/dev/null || echo 2147483647)" ]
}

status() {
    if [ "$kind" != "$V11" ]; then echo available; return 0; fi
    p=$(pidof "$LAUNCHER" || true)
    case "$p" in ''|*[!0-9]*) echo installed; return 0;; esac
    if loaded "$p"; then echo ready; else echo installed; fi
}

# 把本应用加入系统进程白名单（保留原有各项，只追加一行；原文件先备份）。
# 系统只在开机时读取这份名单，所以新加入后要重启一次手表才生效。任何一步不成立都只回 unavailable，不中断调用方。
keepalive() {
    f=/data/system/sys_process_switch_list.xml
    pkg=dev.codex.watchworkshop
    [ -f "$f" ] || { echo unavailable; return 0; }
    if grep -q "<allow_process>$pkg</allow_process>" "$f"; then echo present; return 0; fi
    grep -q '<process_switch_list>' "$f" && grep -q '</process_switch_list>' "$f" || { echo unavailable; return 0; }
    backup="$BASE/sys_process_switch_list.before-owornate.xml"
    if [ ! -e "$backup" ]; then cp -p "$f" "$backup" || { echo unavailable; return 0; }; fi
    tmp="$BASE/sys_process_switch_list.owornate-new"
    before=$(grep -c '<allow_process>' "$f" || true)
    grep -v '</process_switch_list>' "$f" >"$tmp" || { rm -f "$tmp"; echo unavailable; return 0; }
    printf '  <allow_process>%s</allow_process>\n</process_switch_list>\n' "$pkg" >>"$tmp" || { rm -f "$tmp"; echo unavailable; return 0; }
    after=$(grep -c '<allow_process>' "$tmp" || true)
    [ "$after" = "$((before + 1))" ] || { rm -f "$tmp"; echo unavailable; return 0; }
    cat "$tmp" >"$f" || { rm -f "$tmp"; echo unavailable; return 0; }
    rm -f "$tmp"
    grep -q "<allow_process>$pkg</allow_process>" "$f" || { echo unavailable; return 0; }
    echo added
}

[ "$(id -u)" = 0 ] || fail ROOT_REQUIRED
[ "$(getprop ro.product.model)" = OW20W1 ] && [ "$(getprop ro.build.display.id)" = OW20W1_11_A.92 ] || fail DEVICE_UNSUPPORTED
kind=$(sha "$BASE/hook.js")
case "$kind" in "$V9"|"$V10"|"$V11") ;; *) fail SHARED_REQUIRED;; esac
[ "$(sha /data/local/tmp/watchface-theme/theme.jar)" = "$THEME" ] || fail SHARED_REQUIRED
case "${1:-}" in
status) status; exit 0;;
check)
    s=$(status)
    echo "$s"
    if [ "$s" != available ]; then keepalive; fi
    exit 0;;
keepalive)
    r=$(keepalive)
    echo "$r"
    [ "$r" != unavailable ] || exit 1
    exit 0;;
breeno-hide)
    [ "$kind" = "$V11" ] || fail SHARED_REQUIRED
    settings put system codex_qc_hide_breeno 1
    echo hidden; exit 0;;
breeno-show)
    [ "$kind" = "$V11" ] || fail SHARED_REQUIRED
    settings put system codex_qc_hide_breeno 0
    echo shown; exit 0;;
install) ;;
*) fail ACTION_INVALID;;
esac
[ "$(sha "$SOURCE/WatchHide-Launcher-Hook-v11.js")" = "$V11" ] || fail PAYLOAD_INVALID
sh "$BASE/watchhide-bootstrap.sh" status | grep -q '^supervisor_pid=[0-9][0-9]*$' || fail SUPERVISOR_REQUIRED
p=$(pidof "$LAUNCHER")
case "$p" in ''|*[!0-9]*) fail LAUNCHER_UNAVAILABLE;; esac
actual=$(tr '\000' ' ' <"/proc/$p/cmdline")
set -- $actual
[ "$#" = 1 ] && [ "$1" = "$LAUNCHER" ] || fail LAUNCHER_IDENTITY
# Upgrade the shared hook to v11 when an older shared hook is live (v9 or v10).
if [ "$kind" != "$V11" ]; then
    backup="$BASE/hook-before-v11.js"
    if [ ! -e "$backup" ]; then cp -p "$BASE/hook.js" "$backup"; fi
    cp "$SOURCE/WatchHide-Launcher-Hook-v11.js" "$BASE/hook.js.widgets-new"
    [ "$(sha "$BASE/hook.js.widgets-new")" = "$V11" ] || { rm -f "$BASE/hook.js.widgets-new"; fail PAYLOAD_INVALID; }
    chmod 644 "$BASE/hook.js.widgets-new"
    chown 0:0 "$BASE/hook.js.widgets-new"
    mv "$BASE/hook.js.widgets-new" "$BASE/hook.js"
fi
kill -9 "$p" || fail RESTART_FAILED
n=0
while [ "$n" -lt 15 ]; do
    sleep 1
    now=$(pidof "$LAUNCHER" || true)
    if [ -n "$now" ] && [ "$now" != "$p" ] && loaded "$now"; then
        echo ready
        exit 0
    fi
    n=$((n + 1))
done
fail START_UNCONFIRMED
