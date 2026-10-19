#!/usr/bin/env python3
"""Run capture/capture.sh in a temporary Git Bash Android fixture.

The production shell script is copied into each test case. Only container paths,
the /proc lookup, and native command endpoints are redirected to the fixture.
Checks, locking, state transitions, space accounting, and process matching remain
the code under test.
"""

import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
CAPTURE = PROJECT / "capture" / "capture.sh"
DEFAULT_WORK = Path.cwd() / "work" / "native-capture"
WORK = Path(os.environ.get("NATIVE_CAPTURE_TEST_WORK", DEFAULT_WORK)).resolve()
TOKEN = "0123456789abcdef0123456789abcdef"


def git_bash():
    configured = os.environ.get("NATIVE_CAPTURE_BASH")
    candidates = [
        configured,
        r"C:\Program Files\Git\bin\bash.exe",
        r"C:\Program Files\Git\usr\bin\bash.exe",
        shutil.which("bash"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return Path(candidate)
    raise unittest.SkipTest("Git Bash was not found; set NATIVE_CAPTURE_BASH")


def posix_path(path):
    value = Path(path).resolve().as_posix()
    match = re.match(r"^([A-Za-z]):/(.*)$", value)
    return "/" + match.group(1).lower() + "/" + match.group(2) if match else value


def local_path(value):
    if os.name == "nt" and re.match(r"^/[A-Za-z]/", value):
        return Path(value[1].upper() + ":" + value[2:])
    return Path(value)


class Fixture:
    def __init__(self, name):
        WORK.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix=name + "-", dir=str(WORK)))
        self.bin = self.root / "bin"
        self.proc = self.root / "proc"
        self.base = self.root / "device-base"
        self.pictures = self.root / "sdcard" / "Pictures" / "Screenshots"
        self.movies = self.root / "sdcard" / "Movies" / "Screenrecords"
        self.script = self.root / "capture.sh"
        self.bin.mkdir()
        self.proc.mkdir()
        self.log = {
            name: self.root / (name + ".log")
            for name in ("settings", "am", "screencap", "screenrecord", "kill", "signal")
        }
        self._write_commands()
        self._install_script()

    def _write(self, path, body):
        path.write_bytes((body.strip() + "\n").encode("utf-8"))
        os.chmod(path, 0o755)

    def _write_commands(self):
        self._write(self.bin / "id", "#!/bin/sh\necho 0")
        self._write(self.bin / "getprop", r'''#!/bin/sh
case "$1" in
    ro.product.model) echo OW20W1 ;;
    ro.build.display.id) echo OW20W1_11_A.92 ;;
    *) exit 1 ;;
esac''')
        self._write(self.bin / "df", r'''#!/bin/sh
echo 'Filesystem 1024-blocks Used Available Capacity Mounted-on'
echo "fixture 999999 0 ${FAKE_FREE_KB:-100000000} 1% /fixture"''')
        self._write(self.bin / "settings", r'''#!/bin/sh
printf '%s\n' "$*" >> "$CAPTURE_TEST_SETTINGS_LOG"
if [ "$1" = get ] && [ "$2" = system ] && [ "$3" = codex_capture_enabled ]; then
    echo "${CAPTURE_TEST_MODULE_ENABLED:-1}"
    exit 0
fi''')
        self._write(self.bin / "am", r'''#!/bin/sh
printf '%s\n' "$*" >> "$CAPTURE_TEST_AM_LOG"''')
        for name in ("setsid", "nohup"):
            self._write(self.bin / name, r'''#!/bin/sh
exec "$@"''')
        self._write(self.bin / "screencap", r'''#!/bin/sh
printf '%s\n' "$*" >> "$CAPTURE_TEST_SCREENCAP_LOG"
output=$2
printf 'partial' > "$output"
if [ "${FAKE_SCREENCAP_MODE:-ok}" = fail ]; then exit 1; fi
printf 'fixture-png' > "$output"''')
        self._write(self.bin / "screenrecord", r'''#!/bin/sh
printf '%s\n' "$*" >> "$CAPTURE_TEST_SCREENRECORD_LOG"
rate=$2
limit=$4
output=$5
printf 'fixture-mp4' > "$output"
printf '/system/bin/screenrecord --bit-rate %s --time-limit %s %s\000' \
    "$rate" "$limit" "$output" > "$CAPTURE_TEST_PROC/$$.cmdline"
finish() {
    rm -f "$CAPTURE_TEST_PROC/$$.cmdline"
    sh "$(dirname "$0")/am" broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file://$output" >/dev/null 2>&1 || true
    exit 0
}
trap 'printf "INT\n" >> "$CAPTURE_TEST_SIGNAL_LOG"; finish' INT
trap 'printf "TERM\n" >> "$CAPTURE_TEST_SIGNAL_LOG"; finish' TERM
i=0
while [ "$i" -lt 200 ]; do
    if [ -f "$CAPTURE_TEST_PROC/$$.stop" ]; then
        printf 'INT\n' >> "$CAPTURE_TEST_SIGNAL_LOG"
        finish
    fi
    sleep 0.05
    i=$((i + 1))
done
finish''')
        self._write(self.bin / "kill", r'''#!/bin/sh
printf '%s\n' "$*" >> "$CAPTURE_TEST_KILL_LOG"
target=$2
if [ -n "$target" ]; then : > "$CAPTURE_TEST_PROC/$target.stop"; fi
kill "$@"''')

    def _install_script(self):
        source = CAPTURE.read_bytes().decode("utf-8").replace("\r\n", "\n")
        def command(name):
            return "sh " + shlex.quote(posix_path(self.bin / name))

        source = self._replace(source, "BASE=/data/local/tmp/owornate-capture",
                               "BASE=" + shlex.quote(posix_path(self.base)))
        source = self._replace(source, "SOURCE=/data/user/0/dev.codex.watchworkshop/files/capture/capture.sh",
                               "SOURCE=" + shlex.quote(posix_path(self.script)))
        source = self._replace(source, "PICTURES=/sdcard/Pictures/Screenshots",
                               "PICTURES=" + shlex.quote(posix_path(self.pictures)))
        source = self._replace(source, "MOVIES=/sdcard/Movies/Screenrecords",
                               "MOVIES=" + shlex.quote(posix_path(self.movies))
                               + "\nPROC=" + shlex.quote(posix_path(self.proc)))
        source = self._replace(source, '"/proc/$pid/cmdline"', '"$PROC/$pid.cmdline"', 2)
        source = self._replace(source, '"$(id -u)"', '"$(' + command("id") + ' -u)"')
        source = self._replace(source, '"$(getprop ro.product.model)"',
                               '"$(' + command("getprop") + ' ro.product.model)"')
        source = self._replace(source, '"$(getprop ro.build.display.id)"',
                               '"$(' + command("getprop") + ' ro.build.display.id)"')
        source = self._replace(source, 'free=$(df -k "$1"',
                               'free=$(' + command("df") + ' -k "$1"')
        source = self._replace(source, 'settings put system codex_capture_phase',
                               command("settings") + ' put system codex_capture_phase')
        source = self._replace(source, 'am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE',
                               command("am")
                               + ' broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE')
        source = self._replace(source, 'setsid nohup sh "$SOURCE"',
                               command("setsid") + ' ' + command("nohup") + ' sh "$SOURCE"')
        source = self._replace(source, '    /system/bin/screencap -p "$output"',
                               "    sh " + shlex.quote(posix_path(self.bin / "screencap"))
                               + ' -p "$output"')
        source = self._replace(source,
                               '    /system/bin/screenrecord --bit-rate "$rate" --time-limit 180 "$output" > "$BASE/encoder.log" 2>&1 &',
                               "    sh " + shlex.quote(posix_path(self.bin / "screenrecord"))
                               + ' --bit-rate "$rate" --time-limit 180 "$output" > "$BASE/encoder.log" 2>&1 &')
        source = self._replace(source, 'kill -2 "$pid"',
                               "sh " + shlex.quote(posix_path(self.bin / "kill")) + ' -2 "$pid"', 3)
        self.script.write_bytes(source.encode("utf-8"))
        os.chmod(self.script, 0o755)

    @staticmethod
    def _replace(source, old, new, count=1):
        actual = source.count(old)
        if actual != count:
            raise AssertionError("fixture hook expected %d occurrence(s), found %d: %s"
                                 % (count, actual, old))
        return source.replace(old, new)

    def environment(self, free_kb=100000000, screencap="ok", module_enabled="1"):
        env = os.environ.copy()
        env.update({
            "PATH": ":".join((posix_path(self.bin), "/usr/bin", "/bin")),
            "FAKE_FREE_KB": str(free_kb),
            "FAKE_SCREENCAP_MODE": screencap,
            "CAPTURE_TEST_MODULE_ENABLED": str(module_enabled),
            "CAPTURE_TEST_PROC": posix_path(self.proc),
            "CAPTURE_TEST_SETTINGS_LOG": posix_path(self.log["settings"]),
            "CAPTURE_TEST_AM_LOG": posix_path(self.log["am"]),
            "CAPTURE_TEST_SCREENCAP_LOG": posix_path(self.log["screencap"]),
            "CAPTURE_TEST_SCREENRECORD_LOG": posix_path(self.log["screenrecord"]),
            "CAPTURE_TEST_KILL_LOG": posix_path(self.log["kill"]),
            "CAPTURE_TEST_SIGNAL_LOG": posix_path(self.log["signal"]),
        })
        return env

    def run(self, *args, free_kb=100000000, screencap="ok", module_enabled="1", timeout=20):
        return subprocess.run(
            [str(git_bash()), posix_path(self.script), *args],
            cwd=str(self.root), env=self.environment(free_kb, screencap, module_enabled), text=True,
            encoding="utf-8", errors="replace", capture_output=True, timeout=timeout,
        )

    def write_state(self, phase, pid, token, output, started=1700000000000, error=""):
        self.base.mkdir(parents=True, exist_ok=True)
        line = "%s|%s|%s|%s|%s|%s\n" % (phase, pid, token, output, started, error)
        (self.base / "state").write_bytes(line.encode("utf-8"))

    def write_cmdline(self, pid, output, rate=1200000, limit=180):
        value = "/system/bin/screenrecord --bit-rate %s --time-limit %s %s\0" % (
            rate, limit, output)
        (self.proc / (str(pid) + ".cmdline")).write_bytes(value.encode("utf-8"))

    def text(self, name):
        path = self.log[name]
        return path.read_text(encoding="utf-8") if path.exists() else ""


class NativeCaptureTest(unittest.TestCase):
    def fixture(self, name):
        return Fixture(name)

    def assert_json(self, result, phase):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout.strip())
        self.assertEqual(payload["phase"], phase)
        return payload

    def test_status_without_state_does_not_create_device_directories(self):
        f = self.fixture("status-empty")
        result = f.run("status")
        payload = self.assert_json(result, "idle")
        self.assertEqual(payload["pid"], 0)
        self.assertEqual(payload["token"], "none")
        self.assertEqual(payload["path"], "")
        self.assertFalse(f.base.exists())
        self.assertEqual(f.text("settings"), "")
        self.assert_json(f.run("refresh"), "idle")
        self.assertFalse(f.base.exists())

    def test_refresh_recovers_stale_session_and_system_phase(self):
        for phase in ("stopping", "recording", "starting"):
            with self.subTest(phase=phase):
                f = self.fixture("refresh-stale-" + phase)
                output = posix_path(f.movies / "Recording-existing.mp4")
                f.write_state(phase, 4242, TOKEN, output)
                before = (f.base / "state").read_bytes()
                self.assert_json(f.run("status"), "error")
                self.assertEqual((f.base / "state").read_bytes(), before, "status remains read-only")
                payload = self.assert_json(f.run("refresh"), "error")
                self.assertEqual(payload["pid"], 0)
                self.assertEqual(payload["token"], TOKEN)
                self.assertEqual(payload["path"], output)
                self.assertTrue((f.base / "state").read_text().startswith("error|0|"))
                self.assertIn("put system codex_capture_phase error", f.text("settings"))

    def test_cancelled_start_cannot_be_resurrected_by_late_worker(self):
        f = self.fixture("cancel-before-worker")
        output = posix_path(f.movies / "Recording-cancelled.mp4")
        f.write_state("starting", 0, TOKEN, output, started=int(time.time()) * 1000)
        self.assert_json(f.run("stop"), "error")
        worker = f.run("worker", TOKEN, "1200000")
        self.assertEqual(worker.returncode, 1)
        self.assertEqual(worker.stdout.strip(), "SESSION_CHANGED")
        self.assertEqual(f.text("screenrecord"), "")
        self.assertFalse((f.base / "operation.lock").exists())

    def test_disabled_module_is_rechecked_by_worker(self):
        f = self.fixture("disabled-before-worker")
        output = posix_path(f.movies / "Recording-disabled.mp4")
        f.write_state("starting", 0, TOKEN, output, started=int(time.time()) * 1000)
        worker = f.run("worker", TOKEN, "1200000", module_enabled="0")
        self.assertEqual(worker.returncode, 1)
        self.assertEqual(worker.stdout.strip(), "MODULE_DISABLED")
        self.assertEqual(f.text("screenrecord"), "")
        self.assert_json(f.run("status"), "error")

    def test_stop_waits_for_worker_to_publish_its_native_pid(self):
        f = self.fixture("stop-during-launch")
        f.movies.mkdir(parents=True)
        output = posix_path(f.movies / "Recording-interleaving.mp4")
        f.write_state("starting", 0, TOKEN, output, started=int(time.time()) * 1000)
        gate, release = f.root / "at-launch", f.root / "release-worker"
        marker = "    # Detached, finite native worker. No Launcher work and no app lifetime dependency."
        source = f.script.read_text(encoding="utf-8")
        self.assertEqual(source.count(marker), 1)
        pause = "    : > " + shlex.quote(posix_path(gate)) + "\n"
        pause += "    while [ ! -f " + shlex.quote(posix_path(release)) + " ]; do sleep 0.05; done\n"
        f.script.write_bytes(source.replace(marker, pause + marker).encode("utf-8"))
        def spawn(*args):
            return subprocess.Popen([str(git_bash()), posix_path(f.script), *args],
                                    cwd=str(f.root), env=f.environment(), text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        worker, stop = spawn("worker", TOKEN, "1200000"), None
        try:
            deadline = time.monotonic() + 5
            while not gate.exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(gate.exists())
            stop = spawn("stop")
            time.sleep(0.25)
            self.assertIsNone(stop.poll(), "stop must wait while worker owns the launch lock")
            release.touch()
            stdout, stderr = stop.communicate(timeout=15)
            self.assertEqual(stop.returncode, 0, stdout + stderr)
            self.assertEqual(json.loads(stdout)["phase"], "idle")
            worker.communicate(timeout=5)
            self.assertIn("INT", f.text("signal").splitlines())
            self.assert_json(f.run("status"), "idle")
        finally:
            release.touch(exist_ok=True)
            f.run("stop")
            worker.communicate(timeout=15)
            if stop is not None:
                stop.communicate(timeout=15)

    def test_refresh_keeps_a_live_recording_intact(self):
        f = self.fixture("refresh-live")
        started = self.assert_json(f.run("start", "1200000", TOKEN), "recording")
        try:
            current = self.assert_json(f.run("refresh"), "recording")
            self.assertEqual(current["pid"], started["pid"])
            self.assertEqual(current["path"], started["path"])
            self.assertEqual(f.text("kill"), "")
        finally:
            self.assert_json(f.run("stop"), "idle")

    def test_invalid_inputs_fail_before_capture_commands_or_storage(self):
        f = self.fixture("invalid-input")
        cases = [
            (("destroy",), "INVALID_ACTION"),
            (("start", "123", TOKEN), "INVALID_BITRATE"),
            (("start", "1200000", "not-a-token"), "INVALID_SESSION"),
        ]
        for args, message in cases:
            with self.subTest(args=args):
                result = f.run(*args)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout.strip(), message)
                self.assertFalse(f.base.exists())
                for name in ("settings", "am", "screencap", "screenrecord", "kill"):
                    self.assertEqual(f.text(name), "")

    def test_screenshot_uses_fixed_directory_and_scans_the_file(self):
        f = self.fixture("screenshot-ok")
        payload = self.assert_json(f.run("screenshot"), "screenshot")
        output = payload["path"]
        self.assertEqual(Path(output).parent.as_posix(), posix_path(f.pictures))
        self.assertEqual(Path(output).parent.parts[-2:], ("Pictures", "Screenshots"))
        self.assertRegex(Path(output).name, r"^Screenshot-\d{8}-\d{6}-\d+\.png$")
        self.assertEqual(local_path(output).read_bytes(), b"fixture-png")
        scan = f.text("am")
        self.assertIn("android.intent.action.MEDIA_SCANNER_SCAN_FILE", scan)
        self.assertIn("file://" + output, scan)
        self.assertFalse((f.base / "operation.lock").exists())

    def test_screenshot_failure_removes_partial_file_and_does_not_scan(self):
        f = self.fixture("screenshot-fail")
        result = f.run("screenshot", screencap="fail")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "SCREENSHOT_FAILED")
        self.assertEqual(list(f.pictures.glob("Screenshot-*.png")), [])
        self.assertEqual(f.text("am"), "")
        self.assertFalse((f.base / "operation.lock").exists())

    def test_start_rejects_a_live_session_without_launching_another_worker(self):
        f = self.fixture("start-busy")
        output = posix_path(f.movies / ("Recording-existing-" + TOKEN + ".mp4"))
        f.write_state("recording", 4242, TOKEN, output)
        f.write_cmdline(4242, output)
        result = f.run("start", "1200000", TOKEN)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "CAPTURE_BUSY")
        self.assertEqual(f.text("screenrecord"), "")
        self.assertFalse((f.base / "operation.lock").exists())

    def test_start_rejects_low_space_before_publishing_state(self):
        f = self.fixture("start-low-space")
        result = f.run("start", "2400000", TOKEN, free_kb=4096)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "STORAGE_LOW")
        self.assertFalse((f.base / "state").exists())
        self.assertFalse((f.base / "operation.lock").exists())
        self.assertEqual(f.text("screenrecord"), "")

    def test_stop_does_not_signal_a_pid_for_another_output_path(self):
        f = self.fixture("stop-foreign")
        current = posix_path(f.movies / ("Recording-current-" + TOKEN + ".mp4"))
        foreign = posix_path(f.movies / "Recording-foreign-other.mp4")
        f.write_state("recording", 5252, TOKEN, current)
        f.write_cmdline(5252, foreign)
        payload = self.assert_json(f.run("stop"), "error")
        self.assertEqual(payload["pid"], 0)
        self.assertEqual(payload["token"], TOKEN)
        self.assertEqual(payload["path"], current)
        self.assertEqual(payload["error"], "INTERRUPTED")
        self.assertEqual(f.text("kill"), "")

    def test_module_disabled_blocks_capture_but_not_stop_or_status(self):
        f = self.fixture("module-disabled")
        self.assert_json(f.run("status", module_enabled="0"), "idle")
        for args in (("screenshot",), ("start", "1200000", TOKEN)):
            with self.subTest(args=args):
                result = f.run(*args, module_enabled="0")
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout.strip(), "MODULE_DISABLED")
                self.assertFalse(f.base.exists())
                self.assertEqual(f.text("screencap"), "")
                self.assertEqual(f.text("screenrecord"), "")

        started = self.assert_json(f.run("start", "1200000", TOKEN), "recording")
        stopped = self.assert_json(f.run("stop", module_enabled="0"), "idle")
        self.assertEqual(stopped["pid"], 0)
        self.assertEqual(stopped["path"], started["path"])
        self.assertIn("-2 " + str(started["pid"]), f.text("kill"))
        self.assertIn("INT", f.text("signal").splitlines())

    def test_normal_sigint_lets_the_worker_publish_idle(self):
        f = self.fixture("record-stop")
        started = self.assert_json(f.run("start", "1200000", TOKEN), "recording")
        self.assertGreater(started["pid"], 0)
        output = started["path"]
        self.assertTrue(local_path(output).is_file())
        self.assertEqual(Path(output).parent.as_posix(), posix_path(f.movies))
        self.assertEqual(Path(output).parent.parts[-2:], ("Movies", "Screenrecords"))
        command = f.text("screenrecord").strip()
        self.assertIn("--bit-rate 1200000 --time-limit 180", command)
        self.assertTrue(command.endswith(output))

        stopped = self.assert_json(f.run("stop"), "idle")
        self.assertEqual(stopped["pid"], 0)
        self.assertEqual(stopped["token"], TOKEN)
        self.assertEqual(stopped["path"], output)
        self.assertEqual(stopped["error"], "")
        self.assertIn("-2 " + str(started["pid"]), f.text("kill"))
        self.assertIn("INT", f.text("signal").splitlines())
        scan = f.text("am")
        self.assertIn("android.intent.action.MEDIA_SCANNER_SCAN_FILE", scan)
        self.assertIn("file://" + output, scan)


if __name__ == "__main__":
    unittest.main(verbosity=2)
