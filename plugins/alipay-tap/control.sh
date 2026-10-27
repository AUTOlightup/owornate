#!/system/bin/sh
# shell-v1 session plugin. No resident shell, shared-Hook writes or app force-stop.
set -eu
: "${OW_PLUGIN_DATA:?missing data directory}"
APP=com.eg.android.AlipayGphone
APP_HOME=/data/user/0/com.eg.android.AlipayGphone
IPC="$APP_HOME/cache/owornate-alipay-plugin"
STATE="$IPC/state.json"
OWNER="$OW_PLUGIN_DATA/session"
TRIAL="$APP_HOME/cache/owornate-alipay-compat-v1.json"
REVISION=1

fail() { echo "$1" >&2; exit 1; }
report() { echo "OWORNATE_PLUGIN_V1 $1"; }
numeric() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
process_identity() {
    pid=$(pidof "$APP" 2>/dev/null || true)
    numeric "$pid" || return 1
    [ -r "/proc/$pid/stat" ] || return 1
    name=$(tr '\000' '\n' < "/proc/$pid/cmdline" | head -n 1)
    [ "$name" = "$APP" ] || return 1
    started=$(sed 's/^[0-9]* (.*) //' "/proc/$pid/stat" | cut -d ' ' -f20)
    numeric "$started" || return 1
    boot=$(cat /proc/sys/kernel/random/boot_id)
}
owned_process() {
    [ -f "$OWNER" ] || return 1
    process_identity || return 1
    [ "$(sed -n '1p' "$OWNER")" = "$boot $pid $started" ] || return 1
    token=$(sed -n '2p' "$OWNER")
    case "$token" in ''|*[!a-f0-9-]*) fail '插件会话记录损坏';; esac
    [ "${#token}" = 36 ] || fail '插件会话记录损坏'
}
read_state() {
    snapshot=$(cat "$STATE" 2>/dev/null) || return 1
    printf '%s' "$snapshot" | grep -Fq '"schema":"owornate-alipay-plugin-v1"' || return 1
    printf '%s' "$snapshot" | grep -Fq '"token":"'"$token"'"' || return 1
    printf '%s' "$snapshot" | grep -q '"pid":'"$pid"'[,}]' || return 1
    printf '%s' "$snapshot" | grep -q '"revision":'"$REVISION"'[,}]' || return 1
}
has() { printf '%s' "$snapshot" | grep -Fq '"'"$1"'":true'; }
healthy() { ! printf '%s' "$snapshot" | grep -Fq '"error":'; }
status() {
    owned_process || { report disabled; return; }
    read_state || fail '插件会话尚未确认，请稍后刷新；若持续失败，请关闭支付宝后重新开启'
    healthy || fail 'Hook 报告操作异常，请停用或卸载重试；若仍异常，请关闭支付宝后重试'
    if has detached; then report disabled; return; fi
    has ready || fail 'Hook 初始化未就绪，请关闭支付宝后重新开启'
    if has enabled; then report enabled; else report disabled; fi
}
prepare_ipc() {
    app_owner=$(stat -c '%u:%g' "$APP_HOME")
    mkdir -p "$IPC"
    chown "$app_owner" "$IPC"
    chmod 700 "$IPC"
    restorecon "$IPC" >/dev/null 2>&1 || true
}
send_command() {
    printf '%s %s\n' "$token" "$1" > "$IPC/command.next"
    chown "$app_owner" "$IPC/command.next"
    chmod 600 "$IPC/command.next"
    mv "$IPC/command.next" "$IPC/command"
}
wait_state() {
    wanted="$1"
    count=0
    while [ "$count" -lt 12 ]; do
        if read_state && healthy; then
            if [ "$wanted" = uninstall ]; then
                if has ready && has detached && ! has enabled; then return; fi
            elif has ready && ! has detached; then
                if [ "$wanted" = enable ] && has enabled; then return; fi
                if [ "$wanted" = disable ] && ! has enabled; then return; fi
            fi
        fi
        kill -0 "$pid" 2>/dev/null || fail '支付宝进程已退出，请重新开启'
        count=$((count+1))
        sleep 0.2
    done
    fail '插件未确认操作结果，请刷新状态；若仍异常，请关闭支付宝后重试'
}
choose_injector() {
    # Both locations are existing OWOrnate/WatchHide foundations, never overwritten here.
    for candidate in /data/local/tmp/watchworkshop-runtime/frida-inject /data/local/tmp/watchhide/frida-inject; do
        if [ -x "$candidate" ]; then
            version=$(timeout 2 "$candidate" --version 2>/dev/null || true)
            if [ "$version" = 16.7.19 ]; then injector="$candidate"; return; fi
        fi
    done
    fail '缺少已支持的 Frida 16.7.19 运行环境，请先完成 OWOrnate 基础安装'
}
enable() {
    info=$(timeout 2 dumpsys package "$APP") || fail '无法读取支付宝版本'
    printf '%s' "$info" | grep -q 'versionCode=3152 ' || fail '仅支持支付宝 10.6.41.260917（3152）'
    if owned_process; then
        read_state || fail '已有会话尚未确认，请关闭支付宝后重试'
        if ! has detached; then
            has ready || fail '已有 Hook 未就绪，请关闭支付宝后重试'
            prepare_ipc
            send_command enable
            wait_state enable
            timeout 3 am start -n "$APP/com.alipay.watch.ui.MainActivity" >/dev/null 2>&1 || fail '支付宝打开失败，Hook 状态请刷新确认'
            status
            return
        fi
    fi
    # Do not stack with the original experiment's live hooks.
    if process_identity && [ -f "$TRIAL" ] && grep -q '"pid":'"$pid"'[,}]' "$TRIAL"; then
        fail '旧“碰一下试验”仍在运行，请先在旧应用中停止试验'
    fi
    choose_injector
    timeout 3 am start -n "$APP/com.alipay.watch.ui.MainActivity" >/dev/null 2>&1 || fail '支付宝打开失败'
    count=0
    until process_identity; do
        [ "$count" -lt 5 ] || fail '支付宝进程未启动'
        count=$((count+1)); sleep 0.2
    done
    token=$(cat /proc/sys/kernel/random/uuid)
    prepare_ipc
    # Record ownership before injection so a timeout cannot silently allow a second injection.
    printf '%s %s %s\n%s\n' "$boot" "$pid" "$started" "$token" > "$OWNER.next"
    mv "$OWNER.next" "$OWNER"
    send_command enable
    rm -f "$STATE"
    printf 'var OW_ALIPAY_SESSION = {token:"%s",revision:1};\n' "$token" > "$OW_PLUGIN_DATA/payload.js"
    emit_payload >> "$OW_PLUGIN_DATA/payload.js"
    # Native attach ends with -e. The event-driven script lives only in this Alipay process.
    timeout 7 "$injector" -p "$pid" -s "$OW_PLUGIN_DATA/payload.js" -e > "$OW_PLUGIN_DATA/inject.log" 2>&1 || true
    wait_state enable
    status
}
disable() {
    owned_process || { report disabled; return; }
    read_state || fail '无法确认现有 Hook；请关闭支付宝后再停用插件'
    if has detached && healthy && has ready; then report disabled; return; fi
    prepare_ipc
    send_command disable
    wait_state disable
    status
}
uninstall() {
    if owned_process; then
        read_state || fail '无法确认现有 Hook；请关闭支付宝后再卸载插件'
        if has detached && ! healthy; then fail '上次卸载未完整恢复，请关闭支付宝后再卸载'; fi
        if ! has detached; then
            prepare_ipc
            send_command uninstall
            wait_state uninstall
        fi
    fi
    # Remove only this plugin's own IPC and session files; manager config/locks stay intact.
    rm -f "$IPC/command" "$IPC/command.next" "$STATE" "$IPC/state.json.tmp"
    rmdir "$IPC" 2>/dev/null || true
    rm -f "$OWNER" "$OWNER.next" "$OW_PLUGIN_DATA/payload.js" "$OW_PLUGIN_DATA/inject.log"
    report disabled
}

# The build appends emit_payload() and the fixed action dispatch below.
