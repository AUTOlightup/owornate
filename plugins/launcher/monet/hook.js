// launcher-v1: Monet entry. Public host API is globalThis.__watchWorkshop.
(function () {
  const THEME_JAR = '/data/local/tmp/watchworkshop-runtime/theme.jar';
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


  Java.perform(function () {
    const runtime = globalThis.__watchWorkshop;
    if (!runtime) return;
    try { installThemeNow(Java, runtime); } catch (error) { runtime.report('theme', error); }
  });
})();
