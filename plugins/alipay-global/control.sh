#!/system/bin/sh
# shell-v1 adapter for the already verified NFC-process R6. No resident shell.
set -eu
umask 077
: "${OW_PLUGIN_DATA:?missing plugin data directory}"
STATE=/data/user_de/0/com.android.nfc/files/owornate-global-tap-v1.json
ACTION=dev.codex.alipaytrial.GLOBAL_TAP_CONTROL
LEGACY=/data/local/tmp/alipay-global-tap
ATTEMPT="$OW_PLUGIN_DATA/nfc-session"
PAYLOAD="$OW_PLUGIN_DATA/global-tap.js"
REVISION=6

fail() { echo "$1" >&2; exit 1; }
report() { echo "OWORNATE_PLUGIN_V1 $1"; }
numeric() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
process_identity() {
    pid=$(pidof com.android.nfc 2>/dev/null || true)
    numeric "$pid" || return 1
    [ -r "/proc/$pid/stat" ] || return 1
    started=$(sed 's/^[0-9]* (.*) //' "/proc/$pid/stat" | cut -d ' ' -f20)
    numeric "$started" || return 1
    boot=$(cat /proc/sys/kernel/random/boot_id)
    identity="$boot $pid $started"
}
has() { printf '%s' "$snapshot" | grep -Fq '"'"$1"'":true'; }
read_state() {
    snapshot=$(cat "$STATE" 2>/dev/null) || return 1
    printf '%s' "$snapshot" | grep -Fq '"schema":"owornate-global-tap-v1"' || return 1
    printf '%s' "$snapshot" | grep -q '"pid":'"$pid"'[,}]' || return 1
    printf '%s' "$snapshot" | grep -q '"revision":'"$REVISION"'[,}]' || return 1
    has ready
}
attempted() {
    [ ! -f "$ATTEMPT" ] || [ "$(cat "$ATTEMPT")" != "$identity" ] || return 0
    # Coordinate with the existing fallback helper, including an interrupted attach.
    [ ! -f "$LEGACY/injected-pid" ] || [ "$(cat "$LEGACY/injected-pid")" != "$pid" ] || return 0
    return 1
}
probe() {
    process_identity || return 1
    read_state && return 0
    if [ -f "$STATE" ] && grep -q '"pid":'"$pid"'[,}]' "$STATE"; then
        fail '当前 NFC Hook 的版本或就绪状态无法确认，请勿重复注入'
    fi
    if attempted; then fail '当前 NFC 进程已有注入记录但回执缺失，请勿重复开启'; fi
    return 1
}
healthy() {
    if printf '%s' "$snapshot" | grep -Fq '"error":'; then
        fail 'NFC Hook 报告异常；请先退出前台读卡或旧扫描试验，再停用并刷新'
    fi
}
send_command() {
    request=$(cat /proc/sys/kernel/random/uuid)
    timeout 2 am broadcast -a "$ACTION" -p com.android.nfc --es command "$1" \
        --es requestId "$request" --ez allowScreenOff "$2" >/dev/null || fail 'NFC 控制广播未完成，请刷新状态'
    count=0
    while [ "$count" -lt 10 ]; do
        if read_state && printf '%s' "$snapshot" | grep -Fq '"requestId":"'"$request"'"'; then
            healthy
            return
        fi
        kill -0 "$pid" 2>/dev/null || fail 'NFC 进程已退出，请刷新状态'
        count=$((count+1)); sleep 0.15
    done
    fail 'NFC Hook 未确认本次操作，请刷新状态；不要重复注入当前进程'
}
report_state() {
    healthy
    if has enabled; then report enabled; else report disabled; fi
}
setting() {
    allow_off=${OW_SETTING_allow_screen_off:-true}
    case "$allow_off" in true|false) ;; *) fail '息屏配置无效';; esac
}
choose_injector() {
    for candidate in /data/local/tmp/watchworkshop-runtime/frida-inject /data/local/tmp/watchhide/frida-inject; do
        if [ -x "$candidate" ]; then
            version=$(timeout 1 "$candidate" --version 2>/dev/null || true)
            if [ "$version" = 16.7.19 ]; then injector="$candidate"; return; fi
        fi
    done
    fail '需要现有 Frida 16.7.19 基础环境'
}
status() {
    if probe; then
        send_command status false
        report_state
    else
        report disabled
    fi
}
enable() {
    setting
    info=$(timeout 2 dumpsys package com.eg.android.AlipayGphone) || fail '无法读取支付宝版本'
    printf '%s' "$info" | grep -q 'versionCode=3152 ' || fail '仅支持 OPPO 支付宝 10.6.41.260917（3152）'
    if ! probe; then
        process_identity || fail 'NFC 服务未运行'
        choose_injector
        emit_payload > "$PAYLOAD"
        chmod 600 "$PAYLOAD"
        # Mark before attachment; timeout never authorizes a second attachment.
        printf '%s\n' "$identity" > "$ATTEMPT.next"
        mv "$ATTEMPT.next" "$ATTEMPT"
        if [ -d "$LEGACY" ]; then
            printf '%s\n' "$pid" > "$LEGACY/injected-pid.next"
            mv "$LEGACY/injected-pid.next" "$LEGACY/injected-pid"
        fi
        rm -f "$STATE"
        timeout -k 1 8 "$injector" -p "$pid" -s "$PAYLOAD" -e > "$OW_PLUGIN_DATA/inject.log" 2>&1 || true
        count=0
        until read_state; do
            [ "$count" -lt 8 ] || fail 'NFC Hook 尚未就绪，已保留注入记录，请刷新状态'
            kill -0 "$pid" 2>/dev/null || fail 'NFC 进程已退出，请刷新状态'
            count=$((count+1)); sleep 0.15
        done
    fi
    send_command enable "$allow_off"
    has enabled || fail 'NFC 未确认开启'
    if [ "$allow_off" = true ]; then has allowScreenOff || fail '息屏模式未确认';
    else ! has allowScreenOff || fail '亮屏模式未确认'; fi
    # Adoption also preserves the process identity without reinjecting a live R6.
    printf '%s\n' "$identity" > "$ATTEMPT.next"
    mv "$ATTEMPT.next" "$ATTEMPT"
    report_state
}
disable() {
    if probe; then
        send_command disable false
        ! has enabled || fail 'NFC 未确认停用'
    fi
    report disabled
}
configure() {
    setting
    if probe; then
        send_command status false
        if has enabled; then
            send_command enable "$allow_off"
            has enabled || fail '配置后开启状态未确认'
            if [ "$allow_off" = true ]; then has allowScreenOff || fail '息屏配置未确认';
            else ! has allowScreenOff || fail '亮屏配置未确认'; fi
        fi
        report_state
    else
        report disabled
    fi
}
uninstall() {
    disable >/dev/null
    rm -f "$PAYLOAD" "$OW_PLUGIN_DATA/inject.log" "$ATTEMPT.next"
    # Retain session/compat markers and NFC-owned state until that process exits.
    # A disabled hook is deliberately inert, never hot-unloaded or stacked.
    report disabled
}

# build.ps1 appends emit_payload() and fixed action dispatch.
