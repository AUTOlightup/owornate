'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const hookPath = path.join(__dirname, '..', 'plugins', 'alipay-tap', 'hook.js');
const source = fs.readFileSync(hookPath, 'utf8');
const IPC_DIR = '/data/user/0/com.eg.android.AlipayGphone/cache/owornate-alipay-plugin';
const COMMAND_PATH = IPC_DIR + '/command';
const STATE_PATH = IPC_DIR + '/state.json';
const TOKEN = '12345678-1234-5678-9abc-def012345678';
const TOKEN_2 = 'abcdef12-3456-789a-bcde-f0123456789a';

function overloaded(originals, label) {
  const bindings = new Map();
  return {
    overload(...parts) {
      const signature = parts.join(',');
      if (!Object.prototype.hasOwnProperty.call(originals, signature)) {
        throw new Error('unexpected overload ' + label + '(' + signature + ')');
      }
      if (!bindings.has(signature)) {
        const original = originals[signature];
        bindings.set(signature, {
          implementation: null,
          call(receiver, ...args) {
            return original(receiver, ...args);
          },
          invoke(receiver, ...args) {
            const implementation = this.implementation;
            return implementation === null
              ? original(receiver, ...args)
              : implementation.apply(receiver, args);
          }
        });
      }
      return bindings.get(signature);
    }
  };
}

function field(value) {
  return { value };
}

function createHarness(options = {}) {
  const calls = [];
  const logs = [];
  const errors = [];
  const states = [];
  const observers = [];
  const registeredClasses = options.classRegistry || [];
  const files = new Map();
  const directories = new Set([IPC_DIR]);
  const circles = [];
  let stateWriteFailures = options.failStateWriteCount || 0;

  const session = {
    token: options.token || TOKEN,
    revision: options.revision === undefined ? 1 : options.revision
  };
  if (options.initialCommand) files.set(COMMAND_PATH, options.initialCommand + '\n');

  class FakeFile {
    constructor(filePath) {
      this.path = String(filePath);
    }
    exists() {
      return files.has(this.path) || directories.has(this.path);
    }
    mkdirs() {
      directories.add(this.path);
      return true;
    }
    delete() {
      return files.delete(this.path) || directories.delete(this.path);
    }
    renameTo(target) {
      const sourcePath = this.path;
      if (!files.has(sourcePath)) return false;
      const content = files.get(sourcePath);
      files.delete(sourcePath);
      files.set(target.path, content);
      if (target.path === STATE_PATH) states.push(JSON.parse(content));
      return true;
    }
  }
  FakeFile.$new = (...parts) => new FakeFile(parts.join('/').replace(/\/+/g, '/'));

  const FileOutputStream = {
    $new(target, append) {
      const filePath = target instanceof FakeFile ? target.path : String(target);
      return {
        write: overloaded({
          '[B'(receiver, bytes) {
            if (filePath === STATE_PATH + '.tmp' && stateWriteFailures > 0) {
              stateWriteFailures--;
              throw new Error('injected state write failure');
            }
            assert.equal(Boolean(append), false);
            files.set(filePath, Buffer.from(bytes).toString('utf8'));
          }
        }, 'write'),
        flush() {},
        close() {}
      };
    }
  };

  const FileInputStream = {
    $new(file) {
      return {
        content: files.get(file.path) || '',
        close() {}
      };
    }
  };
  const InputStreamReader = {
    $new(input, encoding) {
      assert.equal(encoding, 'UTF-8');
      return { content: input.content };
    }
  };
  const BufferedReader = {
    $new(reader) {
      const lines = String(reader.content).split(/\r?\n/);
      let index = 0;
      return {
        readLine() {
          if (index >= lines.length) return null;
          return lines[index++];
        },
        close() {}
      };
    }
  };

  const packageManager = {
    getPackageInfo: overloaded({
      'java.lang.String,int'(receiver, packageName, flags) {
        assert.equal(packageName, options.packageName || 'com.eg.android.AlipayGphone');
        assert.equal(flags, 0);
        return { versionCode: field(options.versionCode || 3152) };
      }
    }, 'getPackageInfo')
  };
  const application = {
    getPackageName() {
      return options.packageName || 'com.eg.android.AlipayGphone';
    },
    getPackageManager() {
      return packageManager;
    }
  };
  const activityThread = {
    currentApplication() {
      return options.noContext ? null : application;
    }
  };
  const processClass = { myPid: () => 4242 };
  const systemProperties = {
    get: overloaded({
      'java.lang.String'(receiver, key) {
        return key === 'ro.product.model' ? (options.model || 'OW20W1') : '';
      }
    }, 'SystemProperties.get')
  };

  const capabilityF = field(options.capabilityFlag === undefined ? false : options.capabilityFlag);
  const capabilityG = field('2.3.5.32768.3152-original');
  const capabilityBuilds = [];
  const capability = {
    F: capabilityF,
    G: capabilityG,
    a: overloaded({
      boolean(receiver, value) {
        calls.push('capability:flag:' + value);
        capabilityF.value = Boolean(value);
      },
      'android.content.Context'(receiver, app) {
        assert.equal(app, application);
        capabilityG.value = '2.3.5.' + (capabilityF.value ? '32772' : '32768') + '.3152';
        capabilityBuilds.push(capabilityF.value);
        calls.push('capability:build:' + capabilityF.value);
        return capabilityG.value;
      }
    }, 'capability.a')
  };

  const blacklist = {
    a: overloaded({
      ''() {
        calls.push('private-a:original');
        return options.blacklistResult !== undefined ? options.blacklistResult : true;
      }
    }, 'WatchSdk.a.a.a')
  };

  const activityClass = (name) => ({
    getName() {
      return name;
    }
  });
  function activity(name) {
    return {
      getClass() {
        return activityClass(name);
      },
      isFinishing() { return false; },
      isDestroyed() { return false; },
      getWindow() {
        return {
          getDecorView() {
            return { getWindowToken() { return {}; } };
          }
        };
      }
    };
  }

  const circleClass = {
    getDeclaredField(name) {
      assert.ok(name === 'c' || name === 'd');
      return {
        setAccessible(value) { assert.equal(value, true); },
        get(target) { return target[name]; }
      };
    },
    getDeclaredMethod(name, args) {
      assert.equal(name, 'd');
      assert.equal(args.length, 0);
      return {
        setAccessible(value) { assert.equal(value, true); },
        invoke(target) {
          assert.ok(target.c && target.d);
          calls.push('circle:refresh');
        }
      };
    }
  };
  const circle = {
    class: circleClass,
    onResume: overloaded({
      ''() {
        calls.push('circle:resume:original');
        return 'resumed';
      }
    }, 'Circle.onResume')
  };

  const nfcAdapterInstance = { kind: 'NfcAdapter' };
  const nfcAdapter = {
    getDefaultAdapter: overloaded({
      'android.content.Context'(receiver, app) {
        assert.equal(app, application);
        return options.noNfc ? null : nfcAdapterInstance;
      }
    }, 'getDefaultAdapter'),
    enableReaderMode: overloaded({
      'android.app.Activity,android.nfc.NfcAdapter$ReaderCallback,int,android.os.Bundle'(receiver, target) {
        calls.push('nfc:enable:original:' + target.getClass().getName());
        return 'reader-enabled';
      }
    }, 'enableReaderMode'),
    disableReaderMode: overloaded({
      'android.app.Activity'(receiver, target) {
        calls.push('nfc:disable:original:' + target.getClass().getName());
        if (options.failDisableReader) throw new Error('disable reader failed');
        return 'reader-disabled';
      }
    }, 'disableReaderMode'),
    resumePollingVendor: overloaded({
      ''() {
        calls.push('nfc:resume-vendor');
        if (options.failResume) throw new Error('resume failed');
      }
    }, 'resumePollingVendor'),
    pausePollingVendor: overloaded({
      ''() {
        calls.push('nfc:pause-vendor');
        if (options.failPause) throw new Error('pause failed');
      }
    }, 'pausePollingVendor')
  };
  const fileObserver = {
    CLOSE_WRITE: field(8),
    MOVED_TO: field(128)
  };

  const classMap = new Map([
    ['java.io.File', FakeFile],
    ['java.io.FileInputStream', FileInputStream],
    ['java.io.FileOutputStream', FileOutputStream],
    ['java.io.InputStreamReader', InputStreamReader],
    ['java.io.BufferedReader', BufferedReader],
    ['java.lang.String', { $new: (value) => ({ getBytes: (enc) => { assert.equal(enc, 'UTF-8'); return Buffer.from(String(value)); } }) }],
    ['android.os.Process', processClass],
    ['android.os.SystemProperties', systemProperties],
    ['android.os.FileObserver', fileObserver],
    ['android.nfc.NfcAdapter', nfcAdapter],
    ['android.app.ActivityThread', activityThread],
    ['com.alipay.android.watchsdk.a.a', blacklist],
    ['com.alipay.android.watchsdk.g', capability],
    ['com.alipay.watch.ui.home.CircleWatchBoundedActivity', circle]
  ]);

  const Java = {
    available: true,
    use(name) {
      if (!classMap.has(name)) throw new Error('unexpected Java.use ' + name);
      return classMap.get(name);
    },
    perform(callback) { callback(); },
    scheduleOnMainThread(callback) { callback(); },
    retain(value) {
      return new Proxy(value, {
        get(target, property) {
          if (property === '$dispose') return () => {};
          const member = target[property];
          return typeof member === 'function' ? member.bind(target) : member;
        }
      });
    },
    array(type, values) { assert.ok(type.endsWith('Class') || type.endsWith('Object')); return values.slice(); },
    choose(name, callbacks) {
      assert.equal(name, 'com.alipay.watch.ui.home.CircleWatchBoundedActivity');
      for (const instance of circles) {
        if (callbacks.onMatch(instance) === 'stop') break;
      }
      callbacks.onComplete();
    },
    registerClass(spec) {
      assert.equal(registeredClasses.some((entry) => entry.name === spec.name), false,
        'dynamic FileObserver class names stay unique in one process');
      registeredClasses.push(spec);
      return {
        $new(filePath, mask) {
          const instance = {
            $super: { $init(path, value) { assert.equal(path, filePath); assert.equal(value, mask); } },
            filePath,
            mask,
            stopped: false,
            startWatching() { this.started = true; },
            stopWatching() { this.stopped = true; },
            trigger(eventMask, changedPath) {
              spec.methods.onEvent.call(this, eventMask, changedPath);
            }
          };
          assert.deepEqual(Array.from(spec.methods.$init[0].argumentTypes), ['java.lang.String', 'int']);
          spec.methods.$init[0].implementation.call(instance, filePath, mask);
          observers.push(instance);
          return instance;
        }
      };
    }
  };

  const timers = [];
  const context = {
    Java,
    console: {
      log(value) { logs.push(String(value)); },
      error(value) { errors.push(String(value)); }
    },
    setTimeout(callback, delay) {
      timers.push({ callback, delay });
      return timers.length;
    },
    clearTimeout() {},
    Date,
    JSON,
    String,
    Number,
    Object,
    Array,
    RegExp,
    Error
  };

  const prefix = 'var OW_ALIPAY_SESSION = ' + JSON.stringify(session) + ';\n';
  vm.runInNewContext(prefix + source, context, { filename: hookPath });

  function currentState() {
    const value = files.get(STATE_PATH);
    return value === undefined ? null : JSON.parse(value);
  }

  function writeCommand(line) {
    files.set(COMMAND_PATH, line + '\n');
  }

  function observe(pathName, mask = 8) {
    assert.equal(observers.length, 1);
    observers[0].trigger(mask, pathName);
  }

  return {
    calls,
    capability,
    capabilityBuilds,
    capabilityF,
    capabilityG,
    blacklist,
    circle,
    circles,
    observers,
    registeredClasses,
    states,
    errors,
    logs,
    nfcAdapter,
    application,
    currentState,
    writeCommand,
    observe,
    activity,
    failNextStateWrite() { stateWriteFailures++; },
    exposed: {
      blacklist: blacklist.a.overload(),
      circleResume: circle.onResume.overload(),
      enableReader: nfcAdapter.enableReaderMode.overload(
        'android.app.Activity,android.nfc.NfcAdapter$ReaderCallback,int,android.os.Bundle'),
      disableReader: nfcAdapter.disableReaderMode.overload('android.app.Activity')
    }
  };
}

{
  const h = createHarness({ initialCommand: TOKEN + ' enable' });
  assert.deepEqual(h.currentState(), {
    schema: 'owornate-alipay-plugin-v1',
    revision: 1,
    token: TOKEN,
    pid: 4242,
    ready: true,
    enabled: true,
    polling: false,
    detached: false
  });
  assert.equal(h.capabilityF.value, true);
  assert.match(h.capabilityG.value, /32772/);
  assert.deepEqual(h.capabilityBuilds, [true]);
  assert.equal(h.exposed.blacklist.invoke(undefined), false);
  assert.equal(h.registeredClasses[0].name,
    'com.owornate.alipay.Observer_' + TOKEN.replace(/[^A-Za-z0-9]/g, '_'));
  assert.equal(h.observers[0].filePath, IPC_DIR);
  assert.equal(h.observers[0].mask, 136);

  const other = h.activity('com.example.OtherActivity');
  const scan = h.activity('com.alipay.watch.ui.NFCScanActivity');
  assert.equal(h.exposed.enableReader.invoke(h.nfcAdapter, other, null, 0, null), 'reader-enabled');
  assert.equal(h.currentState().polling, false);
  assert.deepEqual(h.calls.slice(-1), ['nfc:enable:original:com.example.OtherActivity']);

  assert.equal(h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null), 'reader-enabled');
  assert.deepEqual(h.calls.slice(-2), [
    'nfc:enable:original:com.alipay.watch.ui.NFCScanActivity',
    'nfc:resume-vendor'
  ]);
  assert.equal(h.currentState().polling, true);
  assert.equal(h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null), 'reader-enabled');
  assert.equal(h.calls.filter((item) => item === 'nfc:resume-vendor').length, 1);

  assert.equal(h.exposed.disableReader.invoke(h.nfcAdapter, scan), 'reader-disabled');
  assert.deepEqual(h.calls.slice(-2), [
    'nfc:disable:original:com.alipay.watch.ui.NFCScanActivity',
    'nfc:pause-vendor'
  ]);
  assert.equal(h.currentState().polling, false);

  const circleActivity = h.activity('com.alipay.watch.ui.home.CircleWatchBoundedActivity');
  circleActivity.c = {};
  circleActivity.d = {};
  h.circles.push(circleActivity);
  assert.equal(h.exposed.circleResume.invoke(circleActivity), 'resumed');
  assert.deepEqual(h.calls.slice(-2), ['circle:resume:original', 'circle:refresh']);

  h.writeCommand(TOKEN + ' disable');
  h.observe('command');
  assert.deepEqual(h.currentState(), {
    schema: 'owornate-alipay-plugin-v1',
    revision: 1,
    token: TOKEN,
    pid: 4242,
    ready: true,
    enabled: false,
    polling: false,
    detached: false
  });
  assert.equal(h.capabilityF.value, false);
  assert.match(h.capabilityG.value, /32768/);
  assert.equal(h.exposed.blacklist.invoke(undefined), true);

  const beforeDisabledCalls = h.calls.length;
  h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null);
  h.exposed.circleResume.invoke(circleActivity);
  assert.deepEqual(h.calls.slice(beforeDisabledCalls), [
    'nfc:enable:original:com.alipay.watch.ui.NFCScanActivity',
    'circle:resume:original'
  ]);
  assert.equal(h.currentState().polling, false);

  const buildsBeforeRepeat = h.capabilityBuilds.length;
  h.observe('command');
  assert.equal(h.currentState().enabled, false);
  assert.equal(h.capabilityBuilds.length, buildsBeforeRepeat + 1,
    'repeated disable reasserts the original capability and does not resume polling');
}

{
  const classRegistry = [];
  const h = createHarness({ classRegistry });
  assert.equal(h.currentState().ready, true);
  assert.equal(h.currentState().enabled, false);
  h.writeCommand(TOKEN + ' enable');
  h.observe('unrelated');
  assert.equal(h.currentState().enabled, false, 'observer only handles command');
  h.observe('command', 128);
  assert.equal(h.currentState().enabled, true);

  const scan = h.activity('com.alipay.watch.ui.NFCScanActivity');
  h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null);
  assert.equal(h.currentState().polling, true);
  const circleActivity = h.activity('com.alipay.watch.ui.home.CircleWatchBoundedActivity');
  circleActivity.c = {};
  circleActivity.d = {};
  h.circles.push(circleActivity);

  h.writeCommand(TOKEN + ' uninstall');
  h.observe('command', 8);
  assert.deepEqual(h.currentState(), {
    schema: 'owornate-alipay-plugin-v1',
    revision: 1,
    token: TOKEN,
    pid: 4242,
    ready: true,
    enabled: false,
    polling: false,
    detached: true
  });
  assert.equal(h.capabilityF.value, false);
  assert.match(h.capabilityG.value, /32768/);
  for (const method of Object.values(h.exposed)) assert.equal(method.implementation, null);
  assert.equal(h.observers[0].stopped, true);
  assert.deepEqual(h.calls.slice(-4), [
    'nfc:pause-vendor',
    'capability:flag:false',
    'capability:build:false',
    'circle:refresh'
  ]);
  const stateCount = h.states.length;

  h.writeCommand(TOKEN + ' enable');
  h.observe('command');
  assert.equal(h.states.length, stateCount, 'detached hook does not write again');
  assert.equal(h.currentState().detached, true);

  const oldObserverClass = h.registeredClasses[0].name;
  const reinstalled = createHarness({
    classRegistry,
    token: TOKEN_2,
    initialCommand: TOKEN_2 + ' enable'
  });
  assert.notEqual(reinstalled.registeredClasses[1].name, oldObserverClass);
  assert.equal(reinstalled.currentState().ready, true);
  assert.equal(reinstalled.currentState().enabled, true);
  assert.equal(h.states.length, stateCount, 'replaced session leaves the old hook terminal');
}

{
  const h = createHarness({ initialCommand: TOKEN + ' enable', failStateWriteCount: 1 });
  assert.deepEqual(h.currentState(), {
    schema: 'owornate-alipay-plugin-v1',
    revision: 1,
    token: TOKEN,
    pid: 4242,
    ready: false,
    enabled: false,
    polling: false,
    detached: false,
    error: 'state-write-failed'
  });
  for (const method of Object.values(h.exposed)) assert.equal(method.implementation, null);
  assert.equal(h.capabilityF.value, false);
  assert.match(h.capabilityG.value, /32768/);
  assert.equal(h.observers[0].stopped, true);
  assert.equal(h.exposed.blacklist.invoke(undefined), true);
  assert.ok(h.errors.some((line) => line.includes('state-write-failed')));
}

{
  const h = createHarness({ initialCommand: TOKEN + ' enable', model: 'OTHER-WATCH' });
  assert.deepEqual(h.currentState(), {
    schema: 'owornate-alipay-plugin-v1',
    revision: 1,
    token: TOKEN,
    pid: 4242,
    ready: false,
    enabled: false,
    polling: false,
    detached: false,
    error: 'model-mismatch'
  });
  assert.equal(h.observers.length, 0);
  for (const method of Object.values(h.exposed)) assert.equal(method.implementation, null);
}

{
  const h = createHarness({ initialCommand: TOKEN + ' enable' });
  h.failNextStateWrite();
  const scan = h.activity('com.alipay.watch.ui.NFCScanActivity');
  h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null);
  assert.equal(h.currentState().ready, false);
  assert.equal(h.currentState().error, 'state-write-failed');
  assert.equal(h.currentState().polling, true,
    'state keeps the real polling result while refusing to claim readiness');
}

{
  const h = createHarness({ initialCommand: TOKEN + ' enable' });
  h.writeCommand('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee enable');
  h.observe('command');
  assert.equal(h.currentState().enabled, true, 'foreign token command is ignored');
  h.writeCommand(TOKEN + ' erase');
  h.observe('command');
  assert.equal(h.currentState().enabled, true, 'unknown command is ignored');
  h.writeCommand(TOKEN + ' disable\nsecond-line');
  h.observe('command');
  assert.equal(h.currentState().enabled, true, 'multi-line command is ignored');
}

{
  const h = createHarness({ initialCommand: TOKEN + ' enable', failDisableReader: true });
  const scan = h.activity('com.alipay.watch.ui.NFCScanActivity');
  h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null);
  assert.throws(() => h.exposed.disableReader.invoke(h.nfcAdapter, scan), /disable reader failed/);
  assert.equal(h.currentState().polling, false, 'original disable failure still releases owned polling');
}

{
  const options = { initialCommand: TOKEN + ' enable', failPause: true };
  const h = createHarness(options);
  const scan = h.activity('com.alipay.watch.ui.NFCScanActivity');
  h.exposed.enableReader.invoke(h.nfcAdapter, scan, null, 0, null);
  h.writeCommand(TOKEN + ' uninstall');
  h.observe('command');
  assert.equal(h.currentState().detached, false, 'failed cleanup remains retryable');
  assert.equal(h.currentState().polling, true);
  assert.equal(h.currentState().error, 'nfc-pause-failed');
  assert.equal(h.observers[0].stopped, false);
  options.failPause = false;
  h.observe('command');
  assert.equal(h.currentState().detached, true);
  assert.equal(h.currentState().polling, false);
}

assert.doesNotMatch(source, /NFCPayMessage|NFCPayCodeActivity|parseLiteFromUri/,
  'no payment message observer');
assert.doesNotMatch(source, /routeResult|routeFailure|sameUserCheck|messageFormat/,
  'no route or payment callback observation');
assert.doesNotMatch(source, /owornate-alipay-trials|journal|history/i,
  'no trial journal');
assert.doesNotMatch(source, /https?:\/\/|amount|merchant|account number/i,
  'no URL or payment data store');
assert.doesNotMatch(source, /Runtime\.getRuntime|force-stop|am force-stop/,
  'the hook never invokes shell or stops Alipay');
assert.match(source, /enableReaderMode/);
assert.match(source, /resumePollingVendor/);
assert.match(source, /disableReaderMode/);
assert.match(source, /pausePollingVendor/);
assert.match(source, /CircleWatchBoundedActivity/);
assert.match(source, /Java\.registerClass/);

console.log('PASS alipay-tap hook lifecycle, NFC ordering, rollback, and bounded source');
