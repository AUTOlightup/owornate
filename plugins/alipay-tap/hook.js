'use strict';

// Loaded into the original Alipay process by the OWOrnate shell-v1 controller.
// The injector prefixes OW_ALIPAY_SESSION = {token, revision: 1} before eval.
(function () {
  var TARGET_PACKAGE = 'com.eg.android.AlipayGphone';
  var TARGET_VERSION_CODE = 3152;
  var TARGET_MODEL = 'OW20W1';
  var SCHEMA = 'owornate-alipay-plugin-v1';
  var IPC_DIRECTORY = '/data/user/0/com.eg.android.AlipayGphone/cache/owornate-alipay-plugin';
  var COMMAND_NAME = 'command';
  var COMMAND_PATH = IPC_DIRECTORY + '/' + COMMAND_NAME;
  var STATE_NAME = 'state.json';
  var STATE_PATH = IPC_DIRECTORY + '/' + STATE_NAME;
  var INIT_TIMEOUT_MS = 3000;
  var RETRY_MS = 100;

  var session = typeof OW_ALIPAY_SESSION === 'object' && OW_ALIPAY_SESSION !== null
    ? OW_ALIPAY_SESSION : null;
  var token = session === null ? '' : String(session.token || '');
  var revision = session === null ? 0 : Number(session.revision);
  var validSession = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(token)
    && revision === 1;

  var started = false;
  var ready = false;
  var enabled = false;
  var polling = false;
  var detached = false;
  var terminal = false;
  var error = '';
  var context = null;
  var pid = 0;
  var originalCapabilityFlag = false;
  var hooks = null;
  var hookRecords = [];
  var observer = null;
  var observerStarted = false;
  var classes = {};

  function FixedError(code) {
    this.code = code;
  }

  function codeOf(value, fallback) {
    return value && value.code ? value.code : fallback;
  }

  function sameName(value, expected) {
    return value !== null && value !== undefined && String(value) === expected;
  }

  function activityClassName(activity) {
    try {
      if (activity === null || activity === undefined) return '';
      return String(activity.getClass().getName());
    } catch (e) {
      return '';
    }
  }

  function isScanActivity(activity) {
    return activityClassName(activity) === 'com.alipay.watch.ui.NFCScanActivity';
  }

  function createDirectory(path) {
    var directory = classes.File.$new(path);
    if (directory.exists()) return directory;
    if (!directory.mkdirs() && !directory.exists()) throw new FixedError('ipc-directory-failed');
    return directory;
  }

  function stateSnapshot() {
    var value = {
      schema: SCHEMA,
      revision: revision,
      token: token,
      pid: pid,
      ready: !!ready,
      enabled: !!enabled,
      polling: !!polling,
      detached: !!detached
    };
    if (error) value.error = error;
    return value;
  }

  function persistState() {
    try {
      createDirectory(IPC_DIRECTORY);
      var target = classes.File.$new(STATE_PATH);
      var temporary = classes.File.$new(STATE_PATH + '.tmp');
      if (temporary.exists() && !temporary.delete()) return false;

      var bytes = classes.String.$new(JSON.stringify(stateSnapshot())).getBytes('UTF-8');
      var output = classes.FileOutputStream.$new(temporary, false);
      try {
        output.write.overload('[B').call(output, bytes);
        output.flush();
      } finally {
        output.close();
      }
      if (!temporary.renameTo(target)) return false;
      return true;
    } catch (e) {
      return false;
    }
  }

  function invalidateState() {
    try {
      var target = classes.File.$new(STATE_PATH);
      if (target.exists()) target.delete();
    } catch (e) {
    }
  }

  function publishAfterChange() {
    if (terminal) return false;
    if (persistState()) return true;
    invalidateState();
    ready = false;
    error = 'state-write-failed';
    persistState();
    return false;
  }

  function readCommand() {
    var input = null;
    var reader = null;
    try {
      var commandFile = classes.File.$new(COMMAND_PATH);
      if (!commandFile.exists()) return '';
      input = classes.FileInputStream.$new(commandFile);
      reader = classes.BufferedReader.$new(classes.InputStreamReader.$new(input, 'UTF-8'));
      var first = reader.readLine();
      if (first === null) return '';
      var second = reader.readLine();
      if (second !== null && String(second).trim().length !== 0) return '';
      var line = String(first).trim();
      return line.length <= 96 ? line : '';
    } catch (e) {
      return '';
    } finally {
      try {
        if (reader !== null) reader.close();
      } catch (e) {
      }
      try {
        if (input !== null) input.close();
      } catch (e) {
      }
    }
  }

  function refreshCircle(activity) {
    var retained = null;
    try {
      retained = Java.retain(activity);
      Java.scheduleOnMainThread(function () {
        try {
          var circle = retained;
          if (circle.isFinishing() || circle.isDestroyed()) return;

          var viewField = classes.Circle.class.getDeclaredField('c');
          var adapterField = classes.Circle.class.getDeclaredField('d');
          viewField.setAccessible(true);
          adapterField.setAccessible(true);
          if (viewField.get(circle) === null || adapterField.get(circle) === null) return;
          if (circle.getWindow().getDecorView().getWindowToken() === null) return;

          var refresh = classes.Circle.class.getDeclaredMethod('d', Java.array('java.lang.Class', []));
          refresh.setAccessible(true);
          refresh.invoke(circle, Java.array('java.lang.Object', []));
        } catch (e) {
        } finally {
          try {
            retained.$dispose();
          } catch (e2) {
          }
        }
      });
    } catch (e) {
      if (retained !== null) {
        try {
          retained.$dispose();
        } catch (e2) {
        }
      }
    }
  }

  function refreshExistingCircle() {
    try {
      Java.choose('com.alipay.watch.ui.home.CircleWatchBoundedActivity', {
        onMatch: function (instance) {
          refreshCircle(instance);
          return 'stop';
        },
        onComplete: function () {
        }
      });
    } catch (e) {
    }
  }

  function installHook(method, wrapper) {
    method.implementation = wrapper;
    hookRecords.push(method);
  }

  function bindHooks() {
    var blacklist = hooks.blacklist;
    installHook(blacklist, function () {
      if (enabled && !detached) return false;
      return blacklist.call(this);
    });

    var circleResume = hooks.circleResume;
    installHook(circleResume, function () {
      var result = circleResume.call(this);
      if (enabled && !detached) refreshCircle(this);
      return result;
    });

    var enableReader = hooks.enableReader;
    installHook(enableReader, function (activity, callback, flags, extras) {
      var result = enableReader.call(this, activity, callback, flags, extras);
      if (enabled && !detached && isScanActivity(activity) && !polling) {
        try {
          hooks.resumeVendor.call(this);
          polling = true;
          error = '';
        } catch (e) {
          polling = false;
          error = 'nfc-resume-failed';
        }
        publishAfterChange();
      }
      return result;
    });

    var disableReader = hooks.disableReader;
    installHook(disableReader, function (activity) {
      try {
        return disableReader.call(this, activity);
      } finally {
        if (enabled && !detached && polling && isScanActivity(activity)) {
        try {
          hooks.pauseVendor.call(this);
          polling = false;
          error = '';
        } catch (e) {
          error = 'nfc-pause-failed';
        }
        publishAfterChange();
        }
      }
    });
  }

  function patchCapability() {
    var nfcPresent = classes.NfcAdapter.getDefaultAdapter
      .overload('android.content.Context').call(classes.NfcAdapter, context) !== null;
    hooks.capabilityFlag.call(classes.Capability, nfcPresent);
    classes.Capability.G.value = null;
    var rebuilt = hooks.capabilityBuild.call(classes.Capability, context);
    if (rebuilt === null) throw new FixedError('capability-build-failed');
  }

  function restoreCapability() {
    hooks.capabilityFlag.call(classes.Capability, originalCapabilityFlag);
    classes.Capability.G.value = null;
    var rebuilt = hooks.capabilityBuild.call(classes.Capability, context);
    if (rebuilt === null) throw new FixedError('capability-restore-failed');
  }

  function pausePolling() {
    if (!polling) return true;
    try {
      hooks.pauseVendor.call(
        classes.NfcAdapter.getDefaultAdapter.overload('android.content.Context')
          .call(classes.NfcAdapter, context));
      polling = false;
      return true;
    } catch (e) {
      error = 'nfc-pause-failed';
      return false;
    }
  }

  function enableMode(strict) {
    try {
      patchCapability();
      enabled = true;
      error = '';
      refreshExistingCircle();
      return true;
    } catch (e) {
      enabled = false;
      if (strict) throw new FixedError(codeOf(e, 'capability-failed'));
      error = codeOf(e, 'capability-failed');
      publishAfterChange();
      return false;
    }
  }

  function disableMode(strict) {
    enabled = false;
    error = '';
    var paused = pausePolling();
    var restored = true;
    try {
      restoreCapability();
    } catch (e) {
      restored = false;
      error = codeOf(e, 'capability-restore-failed');
    }
    refreshExistingCircle();

    if (strict && (!paused || !restored)) {
      throw new FixedError(error || 'disable-failed');
    }
    if (!strict && paused && restored) error = '';
    return paused && restored;
  }

  function stopObserver() {
    if (observer !== null && observerStarted) {
      try {
        observer.stopWatching();
      } catch (e) {
        throw new FixedError('observer-stop-failed');
      }
    }
    observerStarted = false;
  }

  function unbindHooks() {
    var remaining = [];
    for (var i = 0; i < hookRecords.length; i++) {
      try {
        hookRecords[i].implementation = null;
      } catch (e) {
        remaining.push(hookRecords[i]);
      }
    }
    hookRecords = remaining;
    if (remaining.length) throw new FixedError('hook-unbind-failed');
  }

  function uninstallMode() {
    disableMode(true);
    unbindHooks();
    stopObserver();
    ready = true;
    detached = true;
    error = '';
    if (!persistState()) throw new FixedError('state-write-failed');
    terminal = true;
    console.log('owornate-alipay-plugin detached');
  }

  function applyAction(action, initial) {
    if (terminal) return;
    if (action === 'enable') {
      if (!enableMode(initial)) return;
      if (!initial) publishAfterChange();
      return;
    }
    if (action === 'disable') {
      disableMode(initial);
      if (!initial) publishAfterChange();
      return;
    }
    if (action === 'uninstall') uninstallMode();
  }

  function parseCommand(line) {
    var match = /^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}) (enable|disable|uninstall)$/.exec(line);
    if (match === null || match[1] !== token) return '';
    return match[2];
  }

  function processCommand(initial) {
    if (terminal) return;
    var action = parseCommand(readCommand());
    if (!action) return;
    applyAction(action, initial);
  }

  function observerEvent(mask, path) {
    if (terminal) return;
    var closeWrite = classes.FileObserver.CLOSE_WRITE.value;
    var movedTo = classes.FileObserver.MOVED_TO.value;
    var eventMask = Number(mask);
    if ((eventMask & (closeWrite | movedTo)) === 0) return;
    if (!sameName(path, COMMAND_NAME)) return;
    try {
      Java.scheduleOnMainThread(function () {
        if (terminal) return;
        try {
          processCommand(false);
        } catch (e) {
          if (!terminal) {
            error = codeOf(e, 'command-failed');
            publishAfterChange();
          }
        }
      });
    } catch (e) {
    }
  }

  function startObserver() {
    var suffix = token.replace(/[^A-Za-z0-9]/g, '_');
    var observerClassName = 'com.owornate.alipay.Observer_' + suffix;
    var Observer = Java.registerClass({
      name: observerClassName,
      superClass: classes.FileObserver,
      methods: {
        $init: [{
          returnType: 'void',
          argumentTypes: ['java.lang.String', 'int'],
          implementation: function (path, mask) { this.$super.$init(path, mask); }
        }],
        onEvent: function (mask, path) {
          observerEvent(mask, path);
        }
      }
    });
    var mask = classes.FileObserver.CLOSE_WRITE.value | classes.FileObserver.MOVED_TO.value;
    observer = Observer.$new(IPC_DIRECTORY, mask);
    observer.startWatching();
    observerStarted = true;
  }

  function rollbackInitialization(reason) {
    enabled = false;
    try {
      stopObserver();
    } catch (e) {
    }
    if (polling) pausePolling();
    try {
      if (hooks && hooks.capabilityFlag && hooks.capabilityBuild) restoreCapability();
    } catch (e) {
    }
    try { unbindHooks(); } catch (e) { reason = 'hook-unbind-failed'; }
    detached = false;
    polling = false;
    ready = false;
    error = reason || 'initialize-failed';
    invalidateState();
    persistState();
    console.error('owornate-alipay-plugin not ready: ' + error);
  }

  function validateTarget() {
    var packageName = String(context.getPackageName());
    var packageManager = context.getPackageManager();
    var packageInfo = packageManager.getPackageInfo
      .overload('java.lang.String', 'int').call(packageManager, packageName, 0);
    var versionCode = Number(packageInfo.versionCode.value);
    if (packageName !== TARGET_PACKAGE || versionCode !== TARGET_VERSION_CODE) {
      throw new FixedError('target-mismatch');
    }
    var model = String(classes.SystemProperties.get
      .overload('java.lang.String').call(classes.SystemProperties, 'ro.product.model')).trim();
    if (model !== TARGET_MODEL) throw new FixedError('model-mismatch');
  }

  function prepareClasses() {
    prepareStorageClasses();
    classes.SystemProperties = Java.use('android.os.SystemProperties');
    classes.FileObserver = Java.use('android.os.FileObserver');
    classes.NfcAdapter = Java.use('android.nfc.NfcAdapter');
    classes.WatchSdkA = Java.use('com.alipay.android.watchsdk.a.a');
    classes.Capability = Java.use('com.alipay.android.watchsdk.g');
    classes.Circle = Java.use('com.alipay.watch.ui.home.CircleWatchBoundedActivity');

    originalCapabilityFlag = !!classes.Capability.F.value;
    hooks = {
      blacklist: classes.WatchSdkA.a.overload(),
      circleResume: classes.Circle.onResume.overload(),
      enableReader: classes.NfcAdapter.enableReaderMode.overload(
        'android.app.Activity', 'android.nfc.NfcAdapter$ReaderCallback', 'int', 'android.os.Bundle'),
      disableReader: classes.NfcAdapter.disableReaderMode.overload('android.app.Activity'),
      resumeVendor: classes.NfcAdapter.resumePollingVendor.overload(),
      pauseVendor: classes.NfcAdapter.pausePollingVendor.overload(),
      capabilityFlag: classes.Capability.a.overload('boolean'),
      capabilityBuild: classes.Capability.a.overload('android.content.Context')
    };
  }

  function prepareStorageClasses() {
    if (!classes.File) classes.File = Java.use('java.io.File');
    if (!classes.FileInputStream) classes.FileInputStream = Java.use('java.io.FileInputStream');
    if (!classes.FileOutputStream) classes.FileOutputStream = Java.use('java.io.FileOutputStream');
    if (!classes.InputStreamReader) classes.InputStreamReader = Java.use('java.io.InputStreamReader');
    if (!classes.BufferedReader) classes.BufferedReader = Java.use('java.io.BufferedReader');
    if (!classes.String) classes.String = Java.use('java.lang.String');
    if (!classes.Process) classes.Process = Java.use('android.os.Process');
    pid = Number(classes.Process.myPid());
  }

  function initialize(applicationContext) {
    if (terminal || ready) return;
    context = applicationContext;
    try {
      prepareClasses();
      validateTarget();
      createDirectory(IPC_DIRECTORY);
      bindHooks();
      startObserver();
      processCommand(true);
      if (terminal) return;
      ready = true;
      error = '';
      if (!persistState()) throw new FixedError('state-write-failed');
      console.log('owornate-alipay-plugin ready');
    } catch (e) {
      rollbackInitialization(codeOf(e, 'initialize-failed'));
    }
  }

  function findApplication() {
    var ActivityThread = Java.use('android.app.ActivityThread');
    return ActivityThread.currentApplication();
  }

  function attemptInitialization(deadline) {
    if (terminal || ready) return;
    try {
      Java.perform(function () {
        prepareStorageClasses();
        var application = null;
        try {
          application = findApplication();
        } catch (e) {
        }
        if (application !== null) {
          initialize(application);
          return;
        }
        if (Date.now() >= deadline) {
          rollbackInitialization('context-timeout');
          return;
        }
        setTimeout(function () {
          attemptInitialization(deadline);
        }, RETRY_MS);
      });
    } catch (e) {
      if (Date.now() >= deadline) {
        rollbackInitialization('java-timeout');
      } else {
        setTimeout(function () {
          attemptInitialization(deadline);
        }, RETRY_MS);
      }
    }
  }

  if (!validSession) {
    console.error('owornate-alipay-plugin not ready: invalid-session');
    return;
  }
  if (started) return;
  started = true;
  attemptInitialization(Date.now() + INIT_TIMEOUT_MS);
})();
