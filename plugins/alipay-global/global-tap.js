'use strict';

// A.92-only, opt-in normal NFC discovery. No reader-mode or AID changes.
var GLOBAL_STATUS = '/data/user_de/0/com.android.nfc/files/owornate-global-tap-v1.json';
var GLOBAL_ACTION = 'dev.codex.alipaytrial.GLOBAL_TAP_CONTROL';
var globalState = {
  schema: 'owornate-global-tap-v1', revision: 6, pid: 0,
  ready: false, enabled: false, allowScreenOff: false,
  tagCount: 0, matchedCount: 0, launchCount: 0, diagnosticCount: 0,
  lastEvent: 'loading'
};

function queryValue(uri, name) {
  var start = uri.indexOf('?');
  if (start < 0) return null;
  var pairs = uri.slice(start + 1).split('#')[0].split('&');
  for (var i = 0; i < pairs.length; i++) {
    var equal = pairs[i].indexOf('=');
    if (equal < 0) continue;
    if (decodeURIComponent(pairs[i].slice(0, equal)) === name) {
      return decodeURIComponent(pairs[i].slice(equal + 1).replace(/\+/g, ' '));
    }
  }
  return null;
}

function classifyAlipayUri(uri) {
  try {
    var scheme = queryValue(uri, 'scheme');
    if (!scheme) return null;
    // Match the stock parser's getQueryParameter + URLDecoder stages.
    var inner = decodeURIComponent(scheme.replace(/\+/g, ' '));
    var id = queryValue(inner, 'id');
    if (id === '20002153' && queryValue(inner, 't')) return 'BlueRing';
    if (id === '10000007' && /^alipay:\/\/nfc\/(app|h5)\?/.test(inner)) {
      var content = queryValue(inner, 'codeContent');
      if (content && (queryValue(content, 'noT') || queryValue(content, 'nsT'))) return 'BlueRing';
    }
    if (id === '20002123' && queryValue(inner, 'appScheme') === 'NFC_PAY_APP') {
      var params = JSON.parse(queryValue(inner, 'pageParams'));
      if (params && typeof params.k === 'string' && params.k.length) return 'PAY';
    }
  } catch (e) {}
  return null;
}

var globalContext, globalService, globalPower, globalKeyguard;
var globalDispatch, globalCompute, globalApply;
var globalRemoteDiscovered, globalRfSnapshot = null;
var globalControlReceiver, globalScreenReceiver, globalRoutingTask;
var globalOriginalPaused = null;
var globalLastUri = null;
var globalLastLaunch = 0;

function saveGlobalStatus() {
  globalState.pid = Process.id;
  globalState.updatedAt = Date.now();
  try {
    globalState.interactive = Boolean(globalPower.isInteractive());
    globalState.locked = Boolean(globalKeyguard.isKeyguardLocked());
    globalState.readerPresent = globalService.mReaderModeParams.value !== null;
    globalState.pollingPaused = Boolean(globalService.mPollingPaused.value);
    globalState.adapterEnabled = Number(globalService.mState.value) === 3;
    var File = Java.use('java.io.File');
    var file = File.$new(GLOBAL_STATUS);
    file.getParentFile().mkdirs();
    var temp = File.$new(GLOBAL_STATUS + '.tmp');
    var stream = Java.use('java.io.FileOutputStream').$new(temp, false);
    try {
      stream.write.overload('[B').call(stream,
        Java.use('java.lang.String').$new(JSON.stringify(globalState)).getBytes('UTF-8'));
    } finally { stream.close(); }
    if (!temp.renameTo(file)) throw new Error('status-rename-failed');
  } catch (e) { console.error('global-status-write-failed'); }
}

function wakeForGlobalTag() {
  var lock = globalPower.newWakeLock(0x1000000a, 'dev.codex.alipaytrial:global-tag');
  lock.setReferenceCounted(false);
  lock.acquire.overload('long').call(lock, 10000);
}

function vibrateForGlobalTag() {
  try {
    var vibrator = Java.cast(globalContext.getSystemService('vibrator'), Java.use('android.os.Vibrator'));
    globalState.vibrationRequested = false;
    if (vibrator.hasVibrator()) {
      vibrator.vibrate.overload('long').call(vibrator, 80);
      globalState.vibrationRequested = true;
    }
  } catch (e) { globalState.feedbackError = 'vibration-request-failed'; }
}

function launchGlobalAlipay(uri, diagnostic) {
  wakeForGlobalTag();
  var Uri = Java.use('android.net.Uri');
  var Intent = Java.use('android.content.Intent');
  var data = 'alipays://platformapi/startapp?appId=30000030&uri=' +
    String(Uri.encode(uri));
  var intent = Intent.$new('android.intent.action.VIEW');
  intent.setClassName('com.eg.android.AlipayGphone', 'com.alipay.watch.ui.MainActivity');
  intent.setData(Uri.parse(data));
  // MainActivity does not reparse onNewIntent. Standard launch + CLEAR_TOP
  // creates a fresh MainActivity while retaining the account and process.
  intent.addFlags(0x14000000);
  var Binder = Java.use('android.os.Binder');
  var identity = Binder.clearCallingIdentity();
  try { globalContext.startActivity(intent); }
  finally { Binder.restoreCallingIdentity(identity); }
  if (diagnostic) globalState.diagnosticCount++;
  else {
    globalState.launchCount++;
    vibrateForGlobalTag();
  }
}

function queueGlobalRouting(requestId) {
  var task = globalRoutingTask.$new();
  task.requestId.value = requestId || '';
  // Use the same serial AsyncTask executor as the stock routing tasks.
  Java.use('android.os.AsyncTask').execute.overload('java.lang.Runnable').call(
    Java.use('android.os.AsyncTask'), task);
}

Java.perform(function () {
  try {
    var Build = Java.use('android.os.Build');
    if (String(Build.MODEL.value) !== 'OW20W1' || String(Build.DISPLAY.value) !== 'OW20W1_11_A.92') {
      throw new Error('unsupported-firmware');
    }
    globalService = Java.retain(Java.use('com.android.nfc.NfcService').getInstance());
    globalContext = Java.retain(globalService.mContext.value);
    globalPower = Java.cast(globalContext.getSystemService('power'), Java.use('android.os.PowerManager'));
    globalKeyguard = Java.cast(globalContext.getSystemService('keyguard'), Java.use('android.app.KeyguardManager'));
    var Service = Java.use('com.android.nfc.NfcService');
    globalCompute = Service.computeDiscoveryParameters.overload('int');
    globalApply = Service.applyRouting.overload('boolean');
    globalDispatch = Java.use('com.android.nfc.NfcDispatcher').dispatchTag.overload('android.nfc.Tag');
    globalRemoteDiscovered = Service.onRemoteEndpointDiscovered.overload('com.android.nfc.DeviceHost$TagEndpoint');
    globalRemoteDiscovered.implementation = function (endpoint) {
      if (globalState.enabled) {
        try {
          globalRfSnapshot = {
            handle: Number(endpoint.getHandle()), interactive: Boolean(globalPower.isInteractive()),
            screenState: Number(this.mScreenState.value),
            uptime: Number(Java.use('android.os.SystemClock').elapsedRealtime())
          };
        } catch (ignored) { globalRfSnapshot = null; }
      }
      return globalRemoteDiscovered.call(this, endpoint);
    };

    globalCompute.implementation = function (screen) {
      var reader = this.mReaderModeParams.value;
      // A.92 otherwise adds P2P to a retained reader on the OFF branch.
      var computeScreen = globalState.enabled && reader !== null && screen < 4 ? 8 : screen;
      var result = globalCompute.call(this, computeScreen);
      if (globalState.enabled && reader === null) {
        var poll = !globalKeyguard.isKeyguardLocked() &&
          (globalPower.isInteractive() || globalState.allowScreenOff);
        result.mTechMask.value = poll ? 1 : 0; // The verified sticker is NfcA.
        result.mEnableP2p.value = false;
        // Keep normal discovery/LPD and the original SE routing; never set reader=true.
      }
      return result;
    };
    globalApply.implementation = function (force) {
      if (globalState.enabled && this.mReaderModeParams.value === null) {
        this.mPollingPaused.value = false;
      }
      return globalApply.call(this, force);
    };
    globalDispatch.implementation = function (tag) {
      if (!globalState.enabled) return globalDispatch.call(this, tag);
      // A foreground application's dispatch contract takes precedence.
      if (this.mOverrideIntent.value !== null) return globalDispatch.call(this, tag);
      globalState.tagCount++;
      var detectedInteractive = Boolean(globalPower.isInteractive());
      try {
        var ndef = Java.use('android.nfc.tech.Ndef').get(tag);
        var message = ndef === null ? null : ndef.getCachedNdefMessage();
        if (message !== null) {
          var records = message.getRecords();
          globalState.lastNdefRecords = [];
          for (var i = 0; i < records.length; i++) {
            var parsed = records[i].toUri();
            var shape = { tnf: Number(records[i].getTnf()), hasUri: parsed !== null };
            globalState.lastNdefRecords.push(shape);
            var payload = records[i].getPayload();
            // The stock watch SDK reads payload[1..] regardless of TNF/type.
            // Official tags must use the same path, including external records.
            var raw = payload.length > 1 ? String(Java.use('java.lang.String').$new.overload(
              '[B', 'int', 'int', 'java.lang.String').call(Java.use('java.lang.String'), payload, 1, payload.length - 1, 'UTF-8')) : '';
            shape.rawHasScheme = queryValue(raw, 'scheme') !== null;
            var uri = raw;
            var scene = classifyAlipayUri(uri);
            if (scene === null && parsed !== null) {
              uri = String(parsed.toString());
              scene = classifyAlipayUri(uri);
            }
            var shapeUri = Java.use('android.net.Uri').parse(uri);
            globalState.lastUriShape = {
              scheme: shapeUri.getScheme() === null ? null : String(shapeUri.getScheme()),
              host: shapeUri.getHost() === null ? null : String(shapeUri.getHost()),
              hasScheme: queryValue(uri, 'scheme') !== null
            };
            shape.uriScheme = globalState.lastUriShape.scheme;
            shape.hasScheme = globalState.lastUriShape.hasScheme;
            if (shape.hasScheme) {
              try {
                var inner = decodeURIComponent(queryValue(uri, 'scheme').replace(/\+/g, ' '));
                var id = queryValue(inner, 'id');
                shape.id = ['20002153', '10000007', '20002123'].indexOf(id) >= 0 ? id : (id === null ? 'absent' : 'other');
                shape.hasT = Boolean(queryValue(inner, 't'));
                shape.hasNoT = Boolean(queryValue(inner, 'noT'));
                shape.hasNsT = Boolean(queryValue(inner, 'nsT'));
              } catch (ignored) { shape.decodeFailed = true; }
            }
            if (scene === null) continue;
            globalState.matchedCount++;
            globalState.lastScene = scene;
            globalState.lastDetectedInteractive = detectedInteractive;
            try {
              if (globalRfSnapshot !== null && Number(tag.getServiceHandle()) === globalRfSnapshot.handle) {
                globalState.lastRfInteractive = globalRfSnapshot.interactive;
                globalState.lastRfScreenState = globalRfSnapshot.screenState;
                globalState.rfToDispatchMs = Number(Java.use('android.os.SystemClock').elapsedRealtime()) - globalRfSnapshot.uptime;
              }
            } catch (ignored) {}
            if (globalKeyguard.isKeyguardLocked()) {
              wakeForGlobalTag();
              globalState.lastEvent = 'locked-retap-after-unlock';
              saveGlobalStatus();
              return 1;
            }
            if (!globalPower.isInteractive() && !globalState.allowScreenOff) {
              globalState.lastEvent = 'screen-off-ignored';
              saveGlobalStatus();
              return globalDispatch.call(this, tag);
            }
            var now = Number(Java.use('android.os.SystemClock').elapsedRealtime());
            if (uri === globalLastUri && now - globalLastLaunch < 1500) {
              globalState.lastEvent = 'duplicate-tag';
              saveGlobalStatus();
              return 1;
            }
            launchGlobalAlipay(uri, false);
            globalLastUri = uri;
            globalLastLaunch = now;
            globalState.lastEvent = 'alipay-launched';
            delete globalState.error;
            saveGlobalStatus();
            return 1;
          }
        }
        globalState.lastEvent = 'other-tag-passed-through';
      } catch (e) {
        globalState.error = 'tag-bridge-failed';
        globalState.lastEvent = 'bridge-error-passed-through';
      }
      saveGlobalStatus();
      return globalDispatch.call(this, tag);
    };

    globalRoutingTask = Java.registerClass({
      name: 'dev.codex.alipaytrial.GlobalRoutingV6',
      implements: [Java.use('java.lang.Runnable')],
      fields: { requestId: 'java.lang.String' },
      methods: { run: { returnType: 'void', argumentTypes: [], implementation: function () {
        try {
          if (!globalState.enabled && globalOriginalPaused !== null) {
            if (globalService.mReaderModeParams.value !== null) throw new Error('foreground-reader-active');
            globalService.mPollingPaused.value = globalOriginalPaused;
          }
          globalService.applyRouting(true);
          if (!globalState.enabled) globalOriginalPaused = null;
          if (String(this.requestId.value)) globalState.lastEvent = globalState.enabled ? 'enabled' : 'disabled';
          delete globalState.error;
        } catch (e) { globalState.error = String(e.message || 'routing-failed'); }
        if (String(this.requestId.value)) globalState.requestId = String(this.requestId.value);
        saveGlobalStatus();
      } } }
    });

    var Control = Java.registerClass({
      name: 'dev.codex.alipaytrial.GlobalControlV6',
      superClass: Java.use('android.content.BroadcastReceiver'),
      methods: { onReceive: { returnType: 'void', argumentTypes: ['android.content.Context', 'android.content.Intent'], implementation: function (ctx, intent) {
        var command = String(intent.getStringExtra('command'));
        var request = String(intent.getStringExtra('requestId'));
        if (command === 'enable') {
          if (globalService.mReaderModeParams.value !== null) {
            globalState.error = 'stop-foreground-reader-first';
            globalState.requestId = request;
            saveGlobalStatus();
            return;
          }
          if (globalOriginalPaused === null) globalOriginalPaused = Boolean(globalService.mPollingPaused.value);
          globalState.enabled = true;
          globalState.allowScreenOff = intent.getBooleanExtra('allowScreenOff', false);
          queueGlobalRouting(request);
        } else if (command === 'disable') {
          if (globalService.mReaderModeParams.value !== null) {
            globalState.error = 'stop-foreground-reader-first';
            globalState.requestId = request;
            saveGlobalStatus();
            return;
          }
          globalState.enabled = false;
          queueGlobalRouting(request);
        } else if (command === 'diagnostic') {
          try {
            // No scheme/token: stock parser must reject locally, without a payment request.
            launchGlobalAlipay('https://example.invalid/owornate-global-diagnostic', true);
            globalState.lastEvent = 'diagnostic-launch';
            delete globalState.error;
          } catch (e) { globalState.error = 'diagnostic-launch-failed'; }
          globalState.requestId = request;
          saveGlobalStatus();
        } else if (command === 'status') {
          globalState.requestId = request;
          saveGlobalStatus();
        }
      } } }
    });
    var Screen = Java.registerClass({
      name: 'dev.codex.alipaytrial.GlobalScreenV6',
      superClass: Java.use('android.content.BroadcastReceiver'),
      methods: { onReceive: { returnType: 'void', argumentTypes: ['android.content.Context', 'android.content.Intent'], implementation: function () {
        if (globalState.enabled) queueGlobalRouting('');
      } } }
    });
    globalControlReceiver = Control.$new();
    globalScreenReceiver = Screen.$new();
    var Filter = Java.use('android.content.IntentFilter');
    globalContext.registerReceiver.overload('android.content.BroadcastReceiver', 'android.content.IntentFilter', 'java.lang.String', 'android.os.Handler')
      .call(globalContext, globalControlReceiver, Filter.$new(GLOBAL_ACTION), 'android.permission.WRITE_SECURE_SETTINGS', null);
    var filter = Filter.$new();
    filter.addAction('android.intent.action.SCREEN_ON');
    filter.addAction('android.intent.action.SCREEN_OFF');
    filter.addAction('android.intent.action.USER_PRESENT');
    filter.addAction('android.nfc.action.ADAPTER_STATE_CHANGED');
    globalContext.registerReceiver(globalScreenReceiver, filter);
    globalState.ready = true;
    globalState.lastEvent = 'installed-disabled';
    saveGlobalStatus();
  } catch (e) {
    globalState.error = 'install-failed';
    console.error(String(e.stack || e));
    if (globalContext) saveGlobalStatus();
  }
});
