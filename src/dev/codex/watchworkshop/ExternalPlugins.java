package dev.codex.watchworkshop;

import android.content.Context;
import java.io.File;
import java.util.Map;

/** 外部插件只从应用私有安装目录执行；没有 exported shell/路径执行入口。 */
final class ExternalPlugins {
    private ExternalPlugins() {}

    static PluginRepository repository(Context context) {
        return new PluginRepository(new File(context.getApplicationContext().getFilesDir(), "plugins"));
    }

    static boolean run(Context context, String id, String action, Map<String, String> config) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            PluginRepository repository = repository(context);
            return RootShell.session(context, 1800, 15000, adb -> {
                PluginRunner runner = new PluginRunner(repository, command -> {
                    String output = adb.shell(command, 20000);
                    return new PluginRunner.Result(adb.exitCode(), output);
                });
                return action.equals("status") ? runner.read(id) : runner.perform(id, action, config);
            });
        }
    }

    static PluginManifest importPackage(Context context, PluginRepository.Pending pending) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            PluginRepository repository = repository(context);
            PluginManifest current = repository.load(pending.manifest.id);
            if (current == null) return repository.install(pending);
            repository.upgradeFrom(pending);
            if (current.runtime.equals(PluginManifest.LAUNCHER_RUNTIME)) {
                CaptureSettings.preserve(context, current);
                // Importing a new payload does not change the deployed runtime or shared Hook.
                return repository.upgrade(pending, installed -> {});
            }
            return RootShell.session(context, 1800, 15000, adb -> {
                PluginRunner runner = new PluginRunner(repository, command -> {
                    String output = adb.shell(command, 20000);
                    return new PluginRunner.Result(adb.exitCode(), output);
                });
                if (runner.read(current.id)) throw new java.io.IOException("请先停用此插件，再升级；原包和配置未更改。");
                return repository.upgrade(pending, installed -> {
                    if (runner.read(installed.id)) throw new java.io.IOException("新版插件未保持停用状态，已拒绝升级。");
                });
            });
        }
    }
}
