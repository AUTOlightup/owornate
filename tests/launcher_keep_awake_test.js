'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const hookPath = path.join(__dirname, '..', 'plugins', 'examples',
  'launcher-keep-awake', 'hook.js');
const source = fs.readFileSync(hookPath, 'utf8');
const executableSource = source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/.*$/gm, '');

function load(failOnResume, failOnAddFlags) {
  const calls = [];
  const reports = [];
  const Activity = {
    onResume: {
      implementation: null,
      call() {
        calls.push('original:resume');
        if (failOnResume) throw new Error('resume failed');
      }
    },
    onPause: {
      implementation: null,
      call() {
        calls.push('original:pause');
      }
    }
  };
  const context = vm.createContext({
    Java: {
      use(name) {
        assert.equal(name, 'com.heytap.wearable.launcher.Launcher');
        return Activity;
      }
    }
  });
  const factory = vm.runInNewContext(
    '(function (plugin) {\n' + source + '\n})', context);
  const plugin = Object.freeze({
    id: 'dev.example.launcher-keep-awake',
    context: Object.freeze({ packageName: 'com.heytap.wearable.launcher' }),
    report(error) {
      reports.push(String(error && error.message || error));
    }
  });
  factory(plugin);
  return {
    Activity,
    calls,
    reports,
    activity(packageName) {
      return {
        getPackageName() {
          return packageName;
        },
        getWindow() {
          return {
            addFlags(flag) {
              calls.push('window:add:' + flag);
              if (failOnAddFlags) throw new Error('addFlags failed');
            },
            clearFlags(flag) {
              calls.push('window:clear:' + flag);
            }
          };
        }
      };
    }
  };
}

{
  const f = load(false, false);
  assert.equal(f.calls.length, 0, 'factory must not invoke lifecycle methods');
  f.Activity.onResume.implementation.call(f.activity('com.heytap.wearable.launcher'));
  assert.deepEqual(f.calls, ['original:resume', 'window:add:128']);
}

{
  const f = load(false, false);
  f.Activity.onPause.implementation.call(f.activity('com.heytap.wearable.launcher'));
  assert.deepEqual(f.calls, ['original:pause', 'window:clear:128']);
}

{
  const f = load(false, false);
  f.Activity.onResume.implementation.call(f.activity('com.example.other'));
  assert.deepEqual(f.calls, ['original:resume']);
}

{
  const f = load(true, false);
  assert.throws(
    () => f.Activity.onResume.implementation.call(
      f.activity('com.heytap.wearable.launcher')),
    /resume failed/);
  assert.deepEqual(f.calls, ['original:resume']);
  assert.deepEqual(f.reports, ['resume failed']);
}

{
  const f = load(false, true);
  assert.doesNotThrow(
    () => f.Activity.onResume.implementation.call(
      f.activity('com.heytap.wearable.launcher')));
  assert.deepEqual(f.calls, ['original:resume', 'window:add:128']);
  assert.deepEqual(f.reports, ['addFlags failed']);
}

{
  const f = load(false, false);
  f.Activity.onResume.implementation.call(f.activity({ toString: () => 'com.heytap.wearable.launcher' }));
  assert.deepEqual(f.calls, ['original:resume', 'window:add:128']);
}

assert.doesNotMatch(executableSource, /Java\.perform\s*\(/,
  'the runtime supplies Java.perform');
assert.doesNotMatch(executableSource, /android\.provider\.Settings/,
  'the example must not change global settings');
assert.doesNotMatch(executableSource, /setInterval|setTimeout/,
  'the example must not start a daemon');
assert.doesNotMatch(executableSource, /dev\.example\.launcher-keep-awake/,
  'the runtime supplies the plugin id');
assert.match(executableSource, /addFlags\(KEEP_SCREEN_ON\)/);
assert.match(executableSource, /clearFlags\(KEEP_SCREEN_ON\)/);

console.log('PASS launcher-keep-awake factory body and narrow Java mock');
