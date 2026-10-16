"""Exercise the real lifecycle script with simulated Android process/platform commands.

python tests/module_lifecycle_test.py --bash /path/to/bash
This checks file lifecycle and refusal paths, not Frida injection or watch reboot.
"""
from pathlib import Path
import argparse, hashlib, os, shutil, subprocess, tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--bash',default=shutil.which('bash') or 'bash')
args=parser.parse_args()
project=Path(__file__).resolve().parents[1]
work=Path.cwd()/'work'
work.mkdir(exist_ok=True)
count=0
def check(condition,label):
    global count
    assert condition,label
    count+=1

with tempfile.TemporaryDirectory(prefix='module-lifecycle-',dir=work) as scratch:
    root=Path(scratch)
    assert root.resolve().parent==work.resolve()
    base=root/'base';source=root/'source';bin_dir=root/'bin'
    for d in (base,source,bin_dir,root/'proc'/'424242'):d.mkdir(parents=True,exist_ok=True)
    cmdline=root/'proc'/'424242'/'cmdline'
    cmdline.write_bytes(b'com.heytap.wearable.launcher'+b'\0'*48)
    (bin_dir/'getprop').write_text('''#!/bin/sh
case "$1" in
ro.product.model) echo "${SIM_MODEL:-OW20W1}";;
ro.build.display.id) echo OW20W1_11_A.92;;
ro.build.version.sdk) echo 27;;
ro.product.cpu.abi) echo armeabi-v7a;;
esac
''',encoding='utf-8',newline='\n')
    (bin_dir/'id').write_text('#!/bin/sh\necho "${SIM_ROOT:-0}"\n',newline='\n')
    (bin_dir/'pidof').write_text('#!/bin/sh\necho 424242\n',newline='\n')
    for f in bin_dir.iterdir():f.chmod(0o755)
    for name in ('theme.jar','hook.js'):shutil.copyfile(project/'assets'/'monet'/name,source/name)
    (source/'monet-service.sh').write_text('#!/bin/sh\necho "$1" >> ./actions\nexit 0\n',newline='\n')
    frida=b'test platform dependency'
    shared=b'existing unrelated shared hook'
    shared_v10=b'existing v10 shared hook'
    unknown_shared=b'unknown shared hook'
    control=(project/'standalone'/'module-manage.sh').read_text(encoding='utf-8')
    check('0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d' in control,'script pins the shared Hook v10 baseline')
    control=control.replace('BASE=/data/local/tmp/watchworkshop-runtime','BASE=./base').replace('SOURCE=/data/user/0/dev.codex.watchworkshop/files/monet','SOURCE=./source')
    control=control.replace('/data/local/tmp/watchhide/hook.js','./shared.js').replace('/data/local/tmp/watch-monet/hook.js','./legacy.js').replace('/data/local/tmp/watchface-theme/theme.jar','./shared-theme.jar').replace('/proc/','./proc/')
    control=control.replace('e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea',hashlib.sha256(frida).hexdigest()).replace('6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722',hashlib.sha256(shared).hexdigest()).replace('0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d',hashlib.sha256(shared_v10).hexdigest())
    # kill is the one process primitive unavailable on the simulated Android host.
    control='export PATH="$PWD/bin:$PATH"\nkill() { echo "kill $*" >> ./actions; }\n'+control
    (root/'control.sh').write_text(control,encoding='utf-8',newline='\n')
    def run(action,ok=True,**extra):
        p=subprocess.run([args.bash,'--noprofile','--norc','control.sh',action],cwd=root,env={**os.environ,**extra},capture_output=True,text=True,timeout=20)
        check((p.returncode==0)==ok,f'{action}: rc={p.returncode} {p.stdout} {p.stderr}')
        return p.stdout.strip()
    check(run('status')=='setup','no base -> setup')
    (base/'frida-inject').write_bytes(frida);(base/'manager-ready').touch();(base/'install-state.json').write_text('saved settings')
    check(run('status')=='available','base only -> available')
    run('install');check(run('status')=='disabled','install published disabled')
    check(not (root/'actions').exists(),'install does not start a hidden service')
    run('enable');check(run('status')=='installed','enable -> installed')
    # A real Android argv[0] is NUL-padded; extra arguments must still be rejected.
    cmdline.write_bytes(b'com.heytap.wearable.launcher\0unexpected-argument\0')
    output=run('disable',ok=False)
    check('LAUNCHER_IDENTITY' in output,'extra argument refuses Launcher restart')
    check('kill ' not in (root/'actions').read_text(),'unverified process was not killed')
    cmdline.write_bytes(b'com.heytap.wearable.launcher'+b'\0'*48)
    run('enable')
    run('disable');check(run('status')=='disabled','disable persists')
    check('kill -9 424242' in (root/'actions').read_text(),'padded Launcher identity permits real restart action')
    run('enable');run('uninstall')
    check(run('status')=='available','remove -> ready to reinstall')
    check((base/'frida-inject').read_bytes()==frida and (base/'install-state.json').read_text()=='saved settings','base preserved')
    check(all(not (base/f).exists() for f in ('theme.jar','hook.js','monet-service.sh','installed','disabled')),'only module removed')
    run('install');run('enable')
    check(run('status')=='installed','reinstall without computer')
    # Legacy 1.0 standalone modules already have all the manager prerequisites.
    (base/'manager-ready').unlink();run('uninstall')
    check(run('status')=='available','legacy removal retains usable base')
    (base/'hook.js').write_text('unknown')
    run('install',ok=False);check((base/'hook.js').read_text()=='unknown','unknown component preserved')
    (base/'hook.js').unlink()
    (source/'monet-service.sh').rename(source/'service.saved')
    run('install',ok=False)
    check(run('status')=='available' and not (base/'theme.jar').exists(),'failed staging is retryable')
    (source/'service.saved').rename(source/'monet-service.sh')
    (root/'shared.js').write_bytes(shared);shutil.copyfile(source/'theme.jar',root/'shared-theme.jar')
    check(run('status')=='shared','shared Hook v9 detected')
    for action in ('install','enable','disable','uninstall'):run(action,ok=False)
    check((root/'shared.js').read_bytes()==shared,'shared hook never changed by private lifecycle')
    (root/'shared.js').write_bytes(shared_v10)
    check(run('status')=='shared','shared Hook v10 accepted with the helper v6 theme')
    for action in ('install','enable','disable','uninstall'):run(action,ok=False)
    check((root/'shared.js').read_bytes()==shared_v10,'v10 shared hook never changed by private lifecycle')
    (root/'shared.js').write_bytes(unknown_shared)
    check(run('status')=='conflict','unknown shared hook rejected')
    run('install',ok=False)
    check((root/'shared.js').read_bytes()==unknown_shared,'unknown shared hook preserved')
    (root/'shared.js').unlink();(root/'legacy.js').write_text('old runtime')
    check(run('status')=='conflict','legacy conflict');run('install',ok=False)
    (root/'legacy.js').unlink()
    (root/'proc'/'424242'/'maps').write_text('frida-agent-32.so')
    run('install',ok=False)
    check(not (base/'installed').exists(),'foreign running agent blocks installation')
    (root/'proc'/'424242'/'maps').unlink()
    run('status',ok=False,SIM_ROOT='2000');run('install',ok=False,SIM_MODEL='other')
    run('install');(base/'theme.jar').write_bytes(b'unknown helper')
    check(run('status')=='conflict','unknown installed module')
    run('uninstall',ok=False);check((base/'theme.jar').read_bytes()==b'unknown helper','unknown module preserved')
    print(f'Module lifecycle: {count}/{count} checks passed (simulated platform)')
