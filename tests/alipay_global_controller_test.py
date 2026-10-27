"""Run the packaged shell controller against synthetic Android commands; no ADB."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', required=True)
    parser.add_argument('--bash', required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.package) as package:
        source = package.read('plugin.sh').decode('utf-8')
    with tempfile.TemporaryDirectory(prefix='alipay-global-test-') as folder:
        root = Path(folder)
        bins, data, proc, legacy = [root / name for name in ('bin', 'data', 'proc', 'legacy')]
        for directory in (bins, data, legacy, proc / 'sys/kernel/random'):
            directory.mkdir(parents=True)
        for pid in ('333', '444'):
            (proc / pid).mkdir()
            (proc / pid / 'stat').write_text(pid + ' (com.android.nfc) S ' + '0 ' * 18 + '12345 0\n')
        (proc / 'sys/kernel/random/boot_id').write_text('boot-test\n')
        (proc / 'sys/kernel/random/uuid').write_text('test-request\n')
        (root / 'pid').write_text('333')
        (root / 'version').write_text('3152')
        (data / 'config.json').write_text('retained-config')
        state = root / 'state.json'
        posix = root.as_posix()
        bash_root = '/' + root.drive[0].lower() + posix[2:] if root.drive else posix
        env = dict(os.environ, OW_PLUGIN_DATA=data.as_posix(), FAKE_ROOT=bash_root)

        def binary(name, body):
            path = bins / name
            path.write_text('#!/bin/bash\nset -eu\n' + body + '\n', encoding='utf-8', newline='\n')
            path.chmod(0o755)

        binary('id', 'echo 0')
        binary('pidof', 'test -f "$FAKE_ROOT/pid" && cat "$FAKE_ROOT/pid"')
        binary('sleep', 'exit 0')
        binary('timeout', '[ "$1" != -k ] || shift 2; shift; exec "$@"')
        binary('dumpsys', 'echo "versionCode=$(cat "$FAKE_ROOT/version") minSdk=19"')
        binary('am', r'''
action=; request=; off=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --es) case "$2" in command) action="$3";; requestId) request="$3";; esac; shift 3;;
        --ez) off="$3"; shift 3;;
        *) shift;;
    esac
done
[ ! -f "$FAKE_ROOT/no-ack" ] || exit 0
enabled=false; old_off=false; error=
if [ -f "$FAKE_ROOT/state.json" ]; then
    grep -q '"enabled":true' "$FAKE_ROOT/state.json" && enabled=true || true
    grep -q '"allowScreenOff":true' "$FAKE_ROOT/state.json" && old_off=true || true
    grep -q '"error":' "$FAKE_ROOT/state.json" && error=',"error":"routing-failed"' || true
fi
case "$action" in
    enable) enabled=true; old_off="$off"; error=;;
    disable) enabled=false; error=;;
esac
if [ -f "$FAKE_ROOT/reader" ] && [ "$action" != status ]; then
    error=',"error":"stop-foreground-reader-first"'
fi
printf '{"schema":"owornate-global-tap-v1","revision":6,"pid":%s,"ready":true,"enabled":%s,"allowScreenOff":%s,"requestId":"%s"%s}' "$(cat "$FAKE_ROOT/pid")" "$enabled" "$old_off" "$request" "$error" > "$FAKE_ROOT/state.json"
''')
        binary('frida-inject', r'''
if [ "${1-}" = --version ]; then echo 16.7.19; exit 0; fi
echo injected >> "$FAKE_ROOT/injections"
[ ! -f "$FAKE_ROOT/attach-fails" ] || exit 1
printf '{"schema":"owornate-global-tap-v1","revision":6,"pid":%s,"ready":true,"enabled":false,"allowScreenOff":false}' "$(cat "$FAKE_ROOT/pid")" > "$FAKE_ROOT/state.json"
''')
        source = source.replace('/data/user_de/0/com.android.nfc/files/owornate-global-tap-v1.json', state.as_posix())
        source = source.replace('/data/local/tmp/alipay-global-tap', legacy.as_posix())
        source = source.replace('/proc/', proc.as_posix() + '/')
        for path in ('/data/local/tmp/watchworkshop-runtime/frida-inject', '/data/local/tmp/watchhide/frida-inject'):
            source = source.replace(path, (bins / 'frida-inject').as_posix())
        script = root / 'plugin.sh'
        script.write_text(source, encoding='utf-8', newline='\n')
        checks = 0

        def count():
            path = root / 'injections'
            return len(path.read_text().splitlines()) if path.exists() else 0

        def run(action, expected='disabled', error=False, off='true'):
            nonlocal checks
            values = dict(env, OW_SETTING_allow_screen_off=off)
            result = subprocess.run([args.bash, '-c', 'export PATH="$FAKE_ROOT/bin:/usr/bin:/bin"; exec /bin/bash "$1" "$2"', '_', str(script), action], env=values, capture_output=True, encoding='utf-8', timeout=20)
            if error:
                assert result.returncode != 0, (action, result.stdout, result.stderr)
            else:
                assert result.returncode == 0, (action, result.stdout, result.stderr)
                assert result.stdout.strip().splitlines()[-1] == 'OWORNATE_PLUGIN_V1 ' + expected
            checks += 1

        run('status')
        run('configure', off='false')
        assert count() == 0 and not state.exists(), 'read/configure must not initialize'
        # Adopt the prototype's live R6, with no plugin-owned session record.
        state.write_text('{"schema":"owornate-global-tap-v1","revision":6,"pid":333,"ready":true,"enabled":true,"allowScreenOff":true}')
        run('status', 'enabled')
        run('enable', 'enabled')
        assert count() == 0, 'adoption reinjected'
        run('configure', 'enabled', off='false')
        assert json.loads(state.read_text())['allowScreenOff'] is False
        run('disable')
        run('configure', off='true')
        assert json.loads(state.read_text())['enabled'] is False
        run('enable', 'enabled')
        assert count() == 0, 'reenable reinjected'
        original = state.read_text()
        state.unlink()
        run('status', error=True)
        run('enable', error=True)
        assert count() == 0, 'missing receipt retried attach'
        state.write_text(original.replace('"revision":6', '"revision":7'))
        run('status', error=True)
        state.write_text(original)
        (proc / 'sys/kernel/random/uuid').write_text('fresh-unacknowledged-request\n')
        (root / 'no-ack').touch()
        run('status', error=True)
        (root / 'no-ack').unlink()
        (root / 'reader').touch()
        run('disable', error=True)
        (root / 'reader').unlink()
        run('disable')
        run('uninstall')
        run('status')
        assert state.exists() and (data / 'nfc-session').exists(), 'removed inert-hook guard'
        assert (data / 'config.json').read_text() == 'retained-config'
        # New NFC process: old state and markers cannot report enabled.
        (root / 'pid').write_text('444')
        run('status')
        (root / 'version').write_text('3151')
        run('enable', error=True)
        assert count() == 0
        (root / 'version').write_text('3152')
        run('enable', 'enabled')
        assert count() == 1
        run('enable', 'enabled')
        assert count() == 1
        run('configure', error=True, off='invalid')
        run('disable')
        run('enable', 'enabled')
        assert count() == 1
        run('uninstall')
        run('status')
        assert not (data / 'global-tap.js').exists()
        # Failed cold attach must also retain its attempt and reject a retry.
        (root / 'pid').write_text('333')
        (root / 'attach-fails').touch()
        run('enable', error=True)
        assert count() == 2
        run('enable', error=True)
        assert count() == 2, 'failed attach retried on same PID'
        (root / 'pid').unlink()
        run('status')
        print(f'PASS {checks} controller cases: adoption, lifecycle, configuration, restart and failed-attach guard')


if __name__ == '__main__':
    main()
