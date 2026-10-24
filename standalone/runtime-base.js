// WatchWorkshop self-owned Launcher runtime core and standalone Monet helper.
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
  const THEME_JAR = '/data/local/tmp/watchworkshop-runtime/theme.jar';
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
      capture: false
    };
    try { writeMarker(Java, marker); } catch (_) {}
  }

  function create(Java, app) {
    const Log = Java.use('android.util.Log');
    const Settings = Java.use('android.provider.Settings$System');
    const resolver = app.getContentResolver();
    const flags = { monet: false, widgets: false, capture: false };
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
        capture: !!flags.capture
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
      commit() {
        committed = true;
        return write();
      },
      marker
    };
  }

  function installThemeNow(Java, runtime) {
      if (!runtime.enabled('monet')) return;
      const Log = Java.use('android.util.Log');
      const G = Java.use('e.e.c.b.u.h.G');
      const Presenter = Java.use('e.e.c.b.u.e.b');
      const View = Java.use('android.view.View');
      const Thread = Java.use('android.app.ActivityThread');
      const File = Java.use('java.io.File');
      const app = Thread.currentApplication();
      let theme = null;

      function report(error) {
        runtime.report('theme', error);
      }

      function field(object, name) {
        let cls = object.getClass();
        while (cls) {
          try {
            const found = cls.getDeclaredField(name);
            found.setAccessible(true);
            return found.get(object);
          } catch (_) {
            cls = cls.getSuperclass();
          }
        }
        throw new Error('Missing field ' + name);
      }

      function ensure() {
        if (!runtime.enabled('monet')) throw new Error('Monet is disabled');
        if (theme) return theme;
        const cache = File.$new(app.getCodeCacheDir(), 'watchworkshop-monet-v6');
        if (!cache.exists() && !cache.mkdir()) throw new Error('Cannot create code cache');
        const loader = Java.use('dalvik.system.DexClassLoader').$new(
          THEME_JAR, cache.getAbsolutePath(), null, app.getClassLoader()
        );
        const candidate = Java.ClassFactory.get(loader).use('dev.codex.watchtheme.ControlTheme');
        candidate.start(app);
        // ControlTheme restores persisted mode/seed/corners itself, including fixed mode.
        theme = candidate;
        return theme;
      }

      function bind(g) {
        const kept = Java.retain(g);
        Java.scheduleOnMainThread(function () {
          try {
            const bridge = ensure();
            const id = app.getResources().getIdentifier('status_bar', 'id', app.getPackageName());
            const rootView = kept.findViewById.overload('int').call(kept, id);
            if (rootView) bridge.bind(Java.cast(rootView, View));
          } catch (error) {
            report(error);
          } finally {
            kept.$dispose();
          }
        });
      }

      const render = G.a.overload('java.util.concurrent.CopyOnWriteArrayList');
      render.implementation = function (list) {
        const result = render.call(this, list);
        try { bind(this); } catch (error) { report(error); }
        return result;
      };
      const init = G.initView.overload('android.view.View');
      init.implementation = function (view) {
        const result = init.call(this, view);
        try { bind(this); } catch (error) { report(error); }
        return result;
      };
      const details = Presenter.a.overload('android.view.View');
      details.implementation = function (view) {
        let bridge = null;
        try {
          bridge = ensure();
          bridge.beginDetails();
        } catch (error) {
          report(error);
        }
        try {
          const result = details.call(this, view);
          try {
            if (bridge) {
              const data = field(this, 'f');
              if (data) {
                const rootView = field(data, 'a');
                if (rootView) bridge.bindDetails(Java.cast(rootView, View));
              }
            }
          } catch (error) {
            report(error);
          }
          return result;
        } finally {
          if (bridge) bridge.endDetails();
        }
      };

      Java.scheduleOnMainThread(function () {
        try { ensure(); } catch (error) { report(error); }
      });
      let found = 0;
      Java.choose('e.e.c.b.u.h.G', {
        onMatch: function (g) { if (found++ === 0) bind(g); },
        onComplete: function () {}
      });
      if (typeof rpc !== 'undefined') {
        rpc.exports = Object.assign(rpc.exports || {}, {
          status: function () {
            return new Promise(function (resolve) {
              if (!runtime.enabled('monet')) {
                resolve({ enabled: false });
                return;
              }
              Java.scheduleOnMainThread(function () {
                try { resolve(JSON.parse(String(ensure().status()))); }
                catch (error) { resolve({ error: String(error) }); }
              });
            });
          }
        });
      }
      try { Log.i('WatchWorkshopRuntime', 'Monet initialized'); } catch (_) {}
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
      if (!runtime.enabled('monet')) return;
      try {
        installThemeNow(Java, runtime);
      } catch (error) {
        runtime.report('theme', error);
      }
    });
  }

  return {
    MARKER_PATH: MARKER_PATH,
    THEME_JAR: THEME_JAR,
    FLAGS: FLAGS,
    create: create,
    install: install,
    installTheme: installThemeNow,
    writeBootstrapFailure: writeBootstrapFailure,
    writeMarker: writeMarker
  };
});
