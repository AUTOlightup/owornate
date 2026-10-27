"""Execute the packaged controller with fake Android commands; never use ADB."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--bash", required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.package) as package:
        source = package.read("plugin.sh").decode("utf-8")
    with tempfile.TemporaryDirectory(prefix="alipay-controller-") as folder:
        root = Path(folder)
        posix = root.as_posix()
        bins, data, app, proc = [root / n for n in ("bin", "data", "app", "proc")]
        for directory in (bins, data, app / "cache", proc / "333", proc / "sys/kernel/random"):
            directory.mkdir(parents=True)
        (proc / "333/stat").write_text("333 (AlipayGphone) S " + "0 " * 18 + "12345 0\n")
        (proc / "333/cmdline").write_bytes(b"com.eg.android.AlipayGphone\0")
        (proc / "sys/kernel/random/boot_id").write_text("boot-test\n")
        (proc / "sys/kernel/random/uuid").write_text("12345678-1234-1234-1234-123456789abc\n")
        (root / "version").write_text("3152")
        (data / "config.json").write_text("{}")
        (app / "cache/owornate-alipay-trials.jsonl").write_text("old-diagnostics")
        bash_root = '/' + root.drive[0].lower() + posix[2:] if root.drive else posix
        env = dict(os.environ, OW_PLUGIN_DATA=data.as_posix(), FAKE_ROOT=bash_root)

        def binary(name, body):
            path = bins / name
            path.write_text("#!/bin/bash\nset -eu\n" + body + "\n", encoding="utf-8", newline="\n")
            path.chmod(0o755)

        binary("id", "echo 0")
        binary("pidof", 'test -f "$FAKE_ROOT/pid" && cat "$FAKE_ROOT/pid"')
        binary("stat", "echo 0:0")
        for name in ("chown", "restorecon", "sleep"):
            binary(name, "exit 0")
        binary("timeout", 'shift; exec "$@"')
        binary("dumpsys", 'echo "versionCode=$(cat "$FAKE_ROOT/version") minSdk=19"')
        binary("am", 'echo launch >> "$FAKE_ROOT/launches"; echo 333 > "$FAKE_ROOT/pid"')
        update = '''
command=$(cat "$FAKE_ROOT/app/cache/owornate-alipay-plugin/command")
token=${command%% *}; action=${command#* }
enabled=false; detached=false
[ "$action" != enable ] || enabled=true
[ "$action" != uninstall ] || detached=true
printf '{"schema":"owornate-alipay-plugin-v1","revision":1,"token":"%s","pid":333,"ready":true,"enabled":%s,"polling":false,"detached":%s}' "$token" "$enabled" "$detached" > "$FAKE_ROOT/app/cache/owornate-alipay-plugin/state.json"
'''
        binary("frida-inject", '''
if [ "${1-}" = --version ]; then echo 16.7.19; exit 0; fi
echo injected >> "$FAKE_ROOT/injections"
touch "$FAKE_ROOT/loaded"
''' + update)
        binary("mv", '''
/usr/bin/mv "$@"
case "$2" in */command)
  if [ -f "$FAKE_ROOT/loaded" ]; then
''' + update + '''
  fi ;;
esac
''')
        # Only filesystem locations and external commands are replaced, not controller logic.
        source = source.replace("/data/user/0/com.eg.android.AlipayGphone", app.as_posix())
        source = source.replace("/proc/", proc.as_posix() + "/")
        for original in ("/data/local/tmp/watchworkshop-runtime/frida-inject", "/data/local/tmp/watchhide/frida-inject"):
            source = source.replace(original, (bins / "frida-inject").as_posix())
        script = root / "plugin.sh"
        script.write_text(source, encoding="utf-8", newline="\n")
        checks = 0

        def run(action, expected=None, error=False):
            nonlocal checks
            result = subprocess.run([args.bash, '-c', 'export PATH="$FAKE_ROOT/bin:/usr/bin:/bin"; exec /bin/bash "$1" "$2"', '_', str(script), action], env=env,
                                    capture_output=True, encoding="utf-8", timeout=15)
            if error:
                assert result.returncode != 0, (action, result.stdout, result.stderr)
            else:
                assert result.returncode == 0, (action, result.stdout, result.stderr)
                assert result.stdout.strip().splitlines()[-1] == "OWORNATE_PLUGIN_V1 " + expected
            checks += 1
            return result

        run("status", "disabled")
        run("configure", "disabled")
        run("disable", "disabled")
        assert not (root / "launches").exists(), "read/disabled cleanup launched app"
        run("enable", "enabled")
        run("status", "enabled")
        run("enable", "enabled")
        assert (root / "injections").read_text().count("injected") == 1, "duplicate injection"
        run("configure", "enabled")
        run("disable", "disabled")
        run("disable", "disabled")
        run("enable", "enabled")
        assert (root / "injections").read_text().count("injected") == 1, "reenable injected again"
        state = app / "cache/owornate-alipay-plugin/state.json"
        original_state = state.read_text()
        state.write_text(original_state.replace("12345678-1234-1234-1234-123456789abc", "other-token"))
        run("status", error=True)
        run("enable", error=True)
        state.write_text(original_state)
        owner = data / "session"
        original_owner = owner.read_text()
        owner.write_text(original_owner.replace("boot-test", "old-boot"))
        run("status", "disabled")
        owner.write_text(original_owner)
        stale = json.loads(original_state)
        stale["ready"] = False
        state.write_text(json.dumps(stale, separators=(",", ":")))
        run("status", error=True)
        state.write_text(original_state)
        failed_cleanup = json.loads(original_state)
        failed_cleanup.update(enabled=False, polling=True, error="nfc-pause-failed")
        state.write_text(json.dumps(failed_cleanup, separators=(",", ":")))
        run("status", error=True)
        state.write_text(original_state)
        run("uninstall", "disabled")
        run("status", "disabled")
        assert (data / "config.json").read_text() == "{}"
        assert (app / "cache/owornate-alipay-trials.jsonl").read_text() == "old-diagnostics"
        assert not owner.exists()
        (root / "loaded").unlink()
        trial = app / "cache/owornate-alipay-compat-v1.json"
        trial.write_text('{"pid":333,"ready":true,"hookRevision":6}')
        run("enable", error=True)
        trial.unlink()
        (root / "version").write_text("3153")
        run("enable", error=True)
        (root / "version").write_text("3152")
        run("enable", "enabled")
        (root / "pid").unlink()
        run("status", "disabled")
        run("disable", "disabled")
        run("uninstall", "disabled")
        run("not-an-action", error=True)
        print(f"PASS {checks} packaged controller actions; lifecycle, ownership, conflict, cleanup")


if __name__ == "__main__":
    main()
