// Commit after the imported feature hooks have registered.
Java.perform(function () {
  Java.scheduleOnMainThread(function () {
    try {
      if (!globalThis.__watchWorkshop) throw new Error('runtime coordinator missing');
      globalThis.__watchWorkshop.commit();
    } catch (error) {
      try { globalThis.WatchWorkshopRuntimeBase.writeBootstrapFailure(Java, error); } catch (_) {}
    }
  });
});
