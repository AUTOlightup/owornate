package dev.codex.watchworkshop;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** shell-v1 执行协议。Shell 由已认证的 RootShell 提供；本类也能在 JVM 中测试。 */
final class PluginRunner {
    interface Shell { Result run(String command) throws Exception; }
    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    private final PluginRepository repository;
    private final Shell shell;

    PluginRunner(PluginRepository repository, Shell shell) {
        this.repository = repository;
        this.shell = shell;
    }

    boolean read(String id) throws Exception {
        return invoke(id, "status", repository.config(id));
    }

    /** 成功回执后另查真实状态；停用/卸载不删除配置和插件数据。 */
    boolean perform(String id, String action, Map<String, String> config) throws Exception {
        if (!action.equals("enable") && !action.equals("disable")
                && !action.equals("configure") && !action.equals("uninstall")) {
            throw new IOException("不支持的插件操作");
        }
        PluginManifest manifest = repository.load(id);
        if (manifest == null) throw new IOException("插件未安装");
        if (!manifest.runtime.equals("shell-v1")) throw new IOException("请使用 Launcher 插件管理入口");
        Map<String, String> previous = repository.config(id);
        Map<String, String> next = action.equals("configure") ? manifest.validateConfig(config) : previous;
        // A failed stop must never turn into an automatic re-enable on the next boot.
        if (action.equals("disable") || action.equals("uninstall")) repository.setRestoreRequested(id, false);
        // Stop must reach the script even if its previous status cannot be read.
        boolean before = !action.equals("uninstall") && !action.equals("disable") && read(id);
        if (action.equals("uninstall") && invoke(id, "disable", previous)) {
            throw new IOException("插件未确认停用，保留安装包");
        }
        boolean expected = action.equals("enable") || (action.equals("configure") && before);
        boolean result = invoke(id, action, next);
        boolean observed = invoke(id, "status", next);
        if (result != expected || observed != expected) throw new IOException("插件操作结果未确认，请重新读取状态");
        if (action.equals("configure")) repository.saveConfig(id, next);
        if (action.equals("enable") && manifest.restoreOnBoot) repository.setRestoreRequested(id, true);
        if (action.equals("uninstall")) repository.remove(id);
        return observed;
    }

    /** Only a trusted installed package with a successful prior enable may restore. */
    boolean restore(String id) throws Exception {
        PluginManifest manifest = repository.load(id);
        if (manifest == null || !manifest.restoreOnBoot || !repository.restoreRequested(id)) return false;
        Map<String, String> config = repository.config(id);
        boolean result = invoke(id, "restore", config);
        boolean observed = invoke(id, "status", config);
        if (result != observed) throw new IOException("插件自动恢复状态未确认");
        return observed;
    }

    private boolean invoke(String id, String action, Map<String, String> config) throws Exception {
        PluginManifest manifest = repository.load(id);
        if (manifest == null) throw new IOException("插件未安装");
        if (!manifest.runtime.equals("shell-v1")) throw new IOException("请使用 Launcher 插件管理入口");
        Result result = shell.run(command(repository.packageDir(id), repository.dataDir(id),
                action, manifest.validateConfig(config)));
        String output = result.output.trim();
        if (result.code != 0) {
            if (output.contains("OW_PLUGIN_BUSY")) throw new IOException("插件上次操作仍在执行，请稍后重新读取");
            if (output.contains("OW_PLUGIN_DEVICE")) throw new IOException("此插件仅支持 OW20W1 / A.92 / API27 / armv7");
            if (output.contains("OW_PLUGIN_ROOT")) throw new IOException("插件需要管理器的 root 授权");
            throw new IOException("插件执行失败（" + result.code + "）："
                    + output.substring(0, Math.min(output.length(), 300)));
        }
        return parseStatus(output);
    }

    static boolean parseStatus(String output) throws IOException {
        if (output.length() > 8192) throw new IOException("插件回执过长");
        String trimmed = output.trim();
        int line = trimmed.lastIndexOf('\n');
        String last = trimmed.substring(line + 1).trim();
        if (last.equals("OWORNATE_PLUGIN_V1 enabled")) return true;
        if (last.equals("OWORNATE_PLUGIN_V1 disabled")) return false;
        throw new IOException("插件没有返回有效状态");
    }

    static String command(File packageDir, File dataDir, String action,
            Map<String, String> config) throws IOException {
        if (!action.matches("status|enable|disable|configure|uninstall|restore")) throw new IOException("插件动作无效");
        String data = quote(dataDir.getCanonicalPath());
        String lock = quote(new File(dataDir, ".operation-lock").getCanonicalPath());
        String owner = quote(new File(dataDir, ".operation-lock/boot").getCanonicalPath());
        StringBuilder command = new StringBuilder(
                "[ \"$(id -u)\" = 0 ] || { echo OW_PLUGIN_ROOT; exit 1; }; "
                + "[ \"$(getprop ro.product.model)\" = OW20W1 ] && "
                + "[ \"$(getprop ro.build.display.id)\" = OW20W1_11_A.92 ] && "
                + "[ \"$(getprop ro.build.version.sdk)\" = 27 ] && "
                + "[ \"$(getprop ro.product.cpu.abi)\" = armeabi-v7a ] "
                + "|| { echo OW_PLUGIN_DEVICE; exit 1; }; ");
        command.append("owLockBoot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true); ");
        command.append("if ! mkdir ").append(lock).append(" 2>/dev/null; then ");
        command.append("owOldBoot=$(cat ").append(owner).append(" 2>/dev/null || true); ");
        command.append("[ -n \"$owLockBoot\" ] && [ -n \"$owOldBoot\" ] && [ \"$owOldBoot\" != \"$owLockBoot\" ] && rm -f ")
                .append(owner).append(" && rmdir ").append(lock).append(" && mkdir ").append(lock)
                .append(" || { echo OW_PLUGIN_BUSY; exit 1; }; fi; ");
        command.append("printf '%s\\n' \"$owLockBoot\" > ").append(owner).append("; ");
        command.append("trap ").append(quote("rm -f " + owner + "; rmdir " + lock + " 2>/dev/null")).append(" 0; ");
        command.append("export OW_PLUGIN_DATA=").append(data).append("; ");
        for (Map.Entry<String, String> setting : config.entrySet()) {
            if (!setting.getKey().matches("[a-z][a-z0-9_]{0,31}")) throw new IOException("插件设置键无效");
            command.append("export OW_SETTING_").append(setting.getKey()).append('=')
                    .append(quote(setting.getValue())).append("; ");
        }
        command.append("sh ").append(quote(new File(packageDir, "plugin.sh").getCanonicalPath()))
                .append(' ').append(quote(action));
        return command.toString();
    }

    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
