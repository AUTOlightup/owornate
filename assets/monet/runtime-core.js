// WatchWorkshop self-owned Launcher runtime coordinator. Feature code is loaded from imported launcher-v1 packages.
// OPPO Watch2 OW20W1_11_A.92 only. No NFC, WatchHide, QQ or global View hooks.
(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module && module.exports) module.exports = api;
  root.WatchWorkshopRuntimeBase = api;
  if (root.Java) api.install(root.Java);
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const MARKER_PATH = '/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json';
  const EXTENSIONS_SETTING = 'owornate_launcher_plugins';
  const FLAGS = Object.freeze({
    monet: 'codex_monet_enabled',
    widgets: 'codex_widgets_enabled',
    capture: 'codex_capture_enabled'
  });

  function message(error) {
    if (error && error.message) return String(error.message);
    return String(error);
  }

  function writeMarker(Java, marker) {
    const File = Java.use('java.io.File');
    const FileOutputStream = Java.use('java.io.FileOutputStream');
    const StringClass = Java.use('java.lang.String');
    const out = FileOutputStream.$new(File.$new(MARKER_PATH));
    try {
      out.write(StringClass.$new(JSON.stringify(marker)).getBytes('UTF-8'));
    } finally {
      out.close();
    }
  }

  function writeBootstrapFailure(Java, error) {
    let pid = 0;
    try { pid = Number(Java.use('android.os.Process').myPid()); } catch (_) {}
    const marker = {
      version: 2,
      pid: pid,
      error: 'bootstrap: ' + message(error),
      monet: false,
      widgets: false,
      capture: false,
      extensions: {}
    };
    try { writeMarker(Java, marker); } catch (_) {}
  }

  function create(Java, app) {
    const Log = Java.use('android.util.Log');
    const Settings = Java.use('android.provider.Settings$System');
    const resolver = app.getContentResolver();
    const flags = { monet: false, widgets: false, capture: false };
    const extensions = Object.create(null);
    const registered = Object.create(null);
    const errors = [];
    let committed = false;

    function log(error) {
      try { Log.e('WatchWorkshopRuntime', message(error)); } catch (_) {}
    }

    function record(scope, error) {
      const text = String(scope) + ': ' + message(error);
      if (errors.indexOf(text) < 0) errors.push(text);
      log(error);
    }

    function readFlag(scope) {
      try {
        return Settings.getInt
          .overload('android.content.ContentResolver', 'java.lang.String', 'int')
          .call(Settings, resolver, FLAGS[scope], 0) === 1;
      } catch (error) {
        record('bootstrap', FLAGS[scope] + ': ' + message(error));
        return false;
      }
    }

    flags.monet = readFlag('monet');
    flags.widgets = readFlag('widgets');
    flags.capture = readFlag('capture');
    try {
      const raw = Settings.getString
        .overload('android.content.ContentResolver', 'java.lang.String')
        .call(Settings, resolver, EXTENSIONS_SETTING);
      const declared = raw === null ? {} : JSON.parse(String(raw));
      if (!declared || Array.isArray(declared) || typeof declared !== 'object') {
        throw new Error('invalid Launcher plugin mask');
      }
      Object.keys(declared).forEach(id => {
        if (id.length > 80 || !/^[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+$/.test(id)
            || ['dev.owornate.monet', 'dev.owornate.widgets', 'dev.owornate.capture',
              'monet.disabled', 'widgets.disabled', 'capture.disabled'].indexOf(id) >= 0
            || typeof declared[id] !== 'boolean') throw new Error('invalid Launcher plugin mask entry');
        extensions[id] = declared[id];
      });
    } catch (error) { record('bootstrap', error); }

    function marker() {
      let pid = 0;
      try { pid = Number(Java.use('android.os.Process').myPid()); } catch (error) {
        record('bootstrap', 'pid: ' + message(error));
      }
      return {
        version: 2,
        pid: pid,
        error: errors.join('; '),
        monet: !!flags.monet,
        widgets: !!flags.widgets,
        capture: !!flags.capture,
        extensions: Object.assign({}, extensions)
      };
    }

    function write() {
      try {
        writeMarker(Java, marker());
        return true;
      } catch (error) {
        log(error);
        return false;
      }
    }

    return {
      path: MARKER_PATH,
      flags: flags,
      enabled(scope) { return !!flags[scope]; },
      report(scope, error) {
        record(scope, error);
        if (committed) write();
      },
      register(id, factory) {
        if (!Object.prototype.hasOwnProperty.call(extensions, id) || registered[id]) {
          record(id, 'undeclared or duplicate Launcher plugin');
          return;
        }
        registered[id] = true;
        if (!extensions[id]) return;
        try {
          factory(Object.freeze({
            id: id,
            context: app,
            report(error) {
              record(id, error);
              if (committed) write();
            }
          }));
        } catch (error) { record(id, error); }
      },
      commit() {
        Object.keys(extensions).forEach(id => {
          if (!registered[id]) record(id, 'Launcher plugin did not register');
        });
        committed = true;
        return write();
      },
      marker
    };
  }

  function install(Java) {
    const root = typeof globalThis !== 'undefined' ? globalThis : this;
    Java.perform(function () {
      let runtime;
      try {
        const Thread = Java.use('android.app.ActivityThread');
        runtime = create(Java, Thread.currentApplication());
        root.__watchWorkshop = runtime;
      } catch (error) {
        writeBootstrapFailure(Java, error);
        return;
      }
    });
  }

  return {
    MARKER_PATH: MARKER_PATH,
    EXTENSIONS_SETTING: EXTENSIONS_SETTING,
    FLAGS: FLAGS,
    create: create,
    install: install,
    writeBootstrapFailure: writeBootstrapFailure,
    writeMarker: writeMarker
  };
});
