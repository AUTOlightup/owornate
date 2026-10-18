#!/system/bin/sh
# Fixed native capture operations. The recording worker lives for at most 180s.
set -u
BASE=/data/local/tmp/owornate-capture
SOURCE=/data/user/0/dev.codex.watchworkshop/files/capture/capture.sh
PICTURES=/sdcard/Pictures/OWOrnate
MOVIES=/sdcard/Movies/OWOrnate
STATE=$BASE/state
LOCK=$BASE/operation.lock
locked=0
fail() { echo "$1"; exit 1; }
unlock() { if [ "$locked" = 1 ]; then rmdir "$LOCK" 2>/dev/null || true; locked=0; fi; }
trap unlock EXIT
lock() {
    mkdir -p "$BASE" || fail STORAGE_UNAVAILABLE
    chmod 700 "$BASE"
    attempts=0
    until mkdir "$LOCK" 2>/dev/null; do
        [ "$attempts" -lt "${1:-0}" ] || fail CAPTURE_BUSY
        sleep 0.1; attempts=$((attempts+1))
    done
    locked=1
}
load() {
    phase=idle; pid=0; token=none; output=; started=0; error=
    if [ -f "$STATE" ]; then IFS='|' read -r phase pid token output started error < "$STATE"; fi
}
alive() {
    case "$pid" in ''|0|*[!0-9]*) return 1;; esac
    [ -r "/proc/$pid/cmdline" ] || return 1
    actual=$(tr '\000' ' ' < "/proc/$pid/cmdline")
    case "$actual" in "/system/bin/screenrecord --bit-rate "*" --time-limit 180 $output ") return 0;; esac
    return 1
}
publish() {
    printf '%s|%s|%s|%s|%s|%s\n' "$phase" "$pid" "$token" "$output" "$started" "$error" > "$STATE.new" &&
        mv "$STATE.new" "$STATE" || return 1
    # A UI projection only. status validates the real process before returning it.
    settings put system codex_capture_phase "$phase" >/dev/null 2>&1 || true
}
validate() {
    case "$phase" in
        recording|stopping) alive || { phase=error; error=INTERRUPTED; };;
        starting)
            if [ "$pid" != 0 ]; then alive || { phase=error; error=START_FAILED; }
            elif [ "$(date +%s)" -gt "$((started / 1000 + 10))" ]; then phase=error; error=START_FAILED; fi;;
    esac
}
status() {
    load
    validate
    printf '{"phase":"%s","pid":%s,"token":"%s","path":"%s","startedAt":%s,"error":"%s"}\n' "$phase" "$pid" "$token" "$output" "$started" "$error"
}
check_space() {
    mkdir -p "$1" || fail STORAGE_UNAVAILABLE
    required=$2
    free=$(df -k "$1" | tail -n 1)
    set -- $free
    free=${4:-}
    case "$free" in ''|*[!0-9]*) fail STORAGE_UNAVAILABLE;; esac
    [ "$free" -ge "$required" ] || fail STORAGE_LOW
}
scan() { am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$1" >/dev/null 2>&1 || true; }

[ "$(id -u)" = 0 ] || fail ROOT_REQUIRED
[ "$(getprop ro.product.model)" = OW20W1 ] && [ "$(getprop ro.build.display.id)" = OW20W1_11_A.92 ] || fail DEVICE_UNSUPPORTED
umask 077
case "${1:-}" in
status) status;;
refresh)
    # Reconcile only the recording/UI projection; status itself remains read-only.
    [ -f "$STATE" ] || { status; exit 0; }
    lock 50
    load
    validate
    [ "$phase" != error ] || pid=0
    publish || fail STATE_WRITE_FAILED
    status;;
wait)
    wanted=${2:-}
    case "$wanted" in ''|*[!a-f0-9]*) fail INVALID_SESSION;; esac
    n=0
    while [ "$n" -lt 190 ]; do
        load
        [ "$token" = "$wanted" ] || break
        case "$phase" in starting|recording|stopping) ;; *) break;; esac
        if [ "$pid" != 0 ] && ! alive; then
            # The worker gets a moment to finish muxing and publish the result.
            sleep 1
            load
            case "$phase" in recording|stopping) alive || break;; esac
        fi
        sleep 1
        n=$((n+1))
    done
    status;;
screenshot)
    [ "$(settings get system codex_capture_enabled)" = 1 ] || fail MODULE_DISABLED
    lock
    [ "$(settings get system codex_capture_enabled)" = 1 ] || fail MODULE_DISABLED
    check_space "$PICTURES" 2048
    stamp=$(date +%Y%m%d-%H%M%S)-$$
    output="$PICTURES/Screenshot-$stamp.png"
    /system/bin/screencap -p "$output" || { rm -f "$output"; fail SCREENSHOT_FAILED; }
    [ -s "$output" ] || { rm -f "$output"; fail SCREENSHOT_FAILED; }
    scan "$output"
    printf '{"phase":"screenshot","path":"%s"}\n' "$output";;
start)
    rate=${2:-}; wanted=${3:-}
    case "$rate" in 600000|1200000|2400000) ;; *) fail INVALID_BITRATE;; esac
    case "$wanted" in ''|*[!a-f0-9]*) fail INVALID_SESSION;; esac
    [ "${#wanted}" = 32 ] || fail INVALID_SESSION
    [ "$(settings get system codex_capture_enabled)" = 1 ] || fail MODULE_DISABLED
    lock
    [ "$(settings get system codex_capture_enabled)" = 1 ] || fail MODULE_DISABLED
    load
    case "$phase" in
        recording|stopping) alive && fail CAPTURE_BUSY;;
        starting) [ "$(date +%s)" -gt "$((started / 1000 + 10))" ] || fail CAPTURE_BUSY;;
    esac
    # Reserve the full three-minute target budget plus 8 MiB for finalization.
    check_space "$MOVIES" "$((rate * 180 / 8192 + 8192))"
    token=$wanted; output="$MOVIES/Recording-$(date +%Y%m%d-%H%M%S)-$token.mp4"
    started="$(date +%s)000"; phase=starting; pid=0; error=
    publish || fail STATE_WRITE_FAILED
    setsid nohup sh "$SOURCE" worker "$token" "$rate" > "$BASE/worker.log" 2>&1 < /dev/null &
    unlock
    n=0
    while [ "$n" -lt 60 ]; do
        load
        [ "$token" = "$wanted" ] || fail SESSION_CHANGED
        [ "$phase" = starting ] || { status; exit 0; }
        sleep 0.1; n=$((n+1))
    done
    status;;
worker)
    wanted=${2:-}; rate=${3:-}
    case "$wanted" in ''|*[!a-f0-9]*) fail INVALID_SESSION;; esac
    case "$rate" in 600000|1200000|2400000) ;; *) fail INVALID_BITRATE;; esac
    # Stop can cancel pid=0 before this lock, or wait until a real PID is published.
    lock 50
    load
    [ "$token" = "$wanted" ] && [ "$phase" = starting ] || fail SESSION_CHANGED
    if [ "$(settings get system codex_capture_enabled)" != 1 ]; then
        phase=error; pid=0; error=MODULE_DISABLED
        publish || fail STATE_WRITE_FAILED
        fail MODULE_DISABLED
    fi
    # Detached, finite native worker. No Launcher work and no app lifetime dependency.
    /system/bin/screenrecord --bit-rate "$rate" --time-limit 180 "$output" > "$BASE/encoder.log" 2>&1 &
    pid=$!
    trap 'alive && kill -2 "$pid" 2>/dev/null || true' HUP INT TERM
    n=0
    # Give the child time to exec before its /proc command line can be validated.
    while ! alive && kill -0 "$pid" 2>/dev/null && [ "$n" -lt 40 ]; do sleep 0.1; n=$((n+1)); done
    if alive; then
        phase=recording
        publish || { kill -2 "$pid" 2>/dev/null; wait "$pid"; fail STATE_WRITE_FAILED; }
    else
        # This is our unreaped child, so its PID cannot have been reused.
        kill -15 "$pid" 2>/dev/null || true
    fi
    unlock
    wait "$pid"; result=$?
    if alive; then wait "$pid"; result=$?; fi
    # A caller stopping the recording releases the short operation lock before waiting.
    lock 100
    saved_output=$output; saved_started=$started
    load
    if [ "$token" = "$wanted" ]; then
        output=$saved_output; started=$saved_started; pid=0
        if [ "$result" = 0 ] && [ -s "$output" ]; then phase=idle; error=; else phase=error; error=RECORDING_FAILED; fi
        publish
    fi
    unlock
    # screenrecord itself publishes the completed MP4 to MediaScanner.
    ;;
stop)
    lock 50
    load
    case "$phase" in recording|starting|stopping) ;; *) status; exit 0;; esac
    if alive; then
        phase=stopping; error=; publish
        kill -2 "$pid" || fail STOP_FAILED
    else
        phase=error; error=INTERRUPTED; pid=0; publish
    fi
    unlock
    n=0
    while [ "$n" -lt 80 ]; do
        load
        case "$phase" in starting|recording|stopping) ;; *) status; exit 0;; esac
        sleep 0.1; n=$((n+1))
    done
    status;;
*) fail INVALID_ACTION;;
esac
