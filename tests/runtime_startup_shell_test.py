"""Execute RuntimeSupport's actual fixed command with a delayed/failed service."""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--bash', default='bash')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
source = (project / 'src/dev/codex/watchworkshop/RuntimeSupport.java').read_text(encoding='utf-8')
expression = source.split('private static final String COMMAND =', 1)[1].split('\n\n', 1)[0]
command = ''.join(json.loads(value) for value in re.findall(r'"(?:\\.|[^"\\])*"', expression))
command = command.replace('/data/local/tmp/watchhide/hook.js', './shared-hook.js')
command = command.replace('/data/local/tmp/watch-monet/hook.js', './legacy-hook.js')
command = command.replace('/data/local/tmp/watchworkshop-runtime', './base')
command = command.replace('/proc/sys/kernel/random/boot_id', './boot-id')

with tempfile.TemporaryDirectory(prefix='owornate-startup-') as scratch:
    for name, after, legacy, skip, fail in [
        ('ready', 1, False, '', False), ('delayed', 3, False, '', False),
        ('not-ready', 99, False, '', False), ('legacy', 2, True, '', False),
        ('shared', 1, False, 'shared', False), ('disabled', 1, False, 'disabled', False),
        ('extension-only', 1, False, '', False), ('extension-disabled', 1, False, 'extension-disabled', False),
        ('failed-service', 1, False, '', True),
    ]:
        root = Path(scratch) / name
        (root / 'base/modules').mkdir(parents=True)
        (root / 'bin').mkdir()
        (root / 'boot-id').write_text('12345678-1234-1234-1234-123456789abc\n')
        (root / 'base' / ('installed' if legacy else 'runtime-version')).write_text('2\n')
        if skip == 'shared': (root / 'shared-hook.js').touch()
        if name.startswith('extension-'):
            extension = root / 'base/modules/dev.example.disabled'
            extension.mkdir()
            (extension / 'installed').touch()
            if skip == 'extension-disabled': (extension / 'disabled').touch()
        elif skip != 'disabled': (root / 'base/modules/widgets').touch()
        for tool, body in [('id', 'echo 0\n'), ('sleep', 'exit 0\n')]:
            path = root / 'bin' / tool
            path.write_text('#!/bin/sh\n' + body, encoding='utf-8', newline='\n')
            path.chmod(0o755)
        live_key = 'theme_live' if legacy else 'runtime_live'
        service = '''#!/bin/sh
case "$1" in
ensure) echo 'service started'; exit FAIL;;
status)
  n=$(cat ./attempts 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > ./attempts
  live=no; [ "$n" -lt AFTER ] || live=yes
  printf 'service_pid=7\nlauncher_pid=42\nLIVE_KEY=%s\n' "$live";;
esac
'''.replace('FAIL', '1' if fail else '0').replace('AFTER', str(after)).replace('LIVE_KEY', live_key)
        (root / 'base' / ('monet-service.sh' if legacy else 'runtime-service.sh')).write_text(service, encoding='utf-8', newline='\n')
        (root / 'control.sh').write_text('export PATH="$PWD/bin:$PATH"\n' + command + '\n', encoding='utf-8', newline='\n')
        result = subprocess.run([args.bash, '--noprofile', '--norc', './control.sh'], cwd=root,
                                capture_output=True, text=True, timeout=20)
        assert result.returncode == (1 if fail else 0), (name, result.stdout, result.stderr)
        attempts = int((root / 'attempts').read_text()) if (root / 'attempts').exists() else 0
        if skip or fail:
            assert attempts == 0 and '__STATUS__' not in result.stdout, name
        else:
            assert attempts == min(after, 21), (name, attempts)
            expected = 'no' if name == 'not-ready' else 'yes'
            assert '__STATUS__' in result.stdout and f'{live_key}={expected}\n' in result.stdout, name
        print('PASS', name)
print('runtime_startup_shell_test: 9 scenarios passed')
