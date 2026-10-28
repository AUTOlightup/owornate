package dev.codex.watchworkshop;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import dev.codex.watchworkshop.ModuleManagement.Action;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 插件共用管理与声明式设置页，不加载插件提供的 Activity 或 Java 类。 */
public final class PluginActivity extends WatchActivity implements ModuleManagement {
    private static final int PICK = 81, STORAGE = 82;
    private ModuleManagementUi managementUi = new ModuleManagementUi();
    private PluginRepository repository;
    private List<PluginManifest> plugins = new ArrayList<>();
    private List<File> downloads = new ArrayList<>();
    private PluginManifest manifest;
    private PluginRepository.Pending pending;
    private Map<String, String> config = new LinkedHashMap<>();
    private String selected = "", pendingToken = "", setting = "";
    private String error = "", operationError = "", notice = "";
    private boolean busy, confirmed, showDownloads;
    private boolean managementOnly, returnToCaller;
    private boolean descriptionExpanded, managementExpanded, technicalExpanded;
    private boolean configurationLoaded;
    private Boolean enabled;
    private LauncherPlugins.Snapshot launcherState;

    private boolean launcher() { return manifest != null && manifest.runtime.equals("launcher-v1"); }
    private boolean nativeFeature() { return launcher() && !manifest.launcherExtension(); }
    private boolean canConfigure() { return !busy && confirmed; }

    static Intent managementIntent(Context context, String id) {
        return new Intent(context, PluginActivity.class).putExtra("pluginId", id)
                .putExtra("managementOnly", true);
    }

    /** 已有完整原生设置的插件只有一个功能入口；其余继续使用声明式页面。 */
    static Intent settingsIntent(Context context, PluginManifest plugin) {
        if (PluginManifest.LAUNCHER_RUNTIME.equals(plugin.runtime) && !plugin.launcherExtension()) {
            if ("capture".equals(plugin.feature())) return new Intent(context, CaptureSetupActivity.class);
            return new Intent(context, MainActivity.class).putExtra("pluginFeature", plugin.feature());
        }
        return new Intent(context, PluginActivity.class).putExtra("pluginId", plugin.id);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        repository = ExternalPlugins.repository(this);
        selected = saved == null ? getIntent().getStringExtra("pluginId") : saved.getString("pluginId", "");
        if (selected == null) selected = "";
        managementOnly = saved == null ? getIntent().getBooleanExtra("managementOnly", false)
                : saved.getBoolean("managementOnly");
        returnToCaller = saved == null ? !selected.isEmpty() : saved.getBoolean("returnToCaller");
        if (saved != null) {
            pendingToken = saved.getString("pending", "");
            setting = saved.getString("setting", "");
            descriptionExpanded = saved.getBoolean("descriptionExpanded");
            managementExpanded = saved.getBoolean("managementExpanded");
            technicalExpanded = saved.getBoolean("technicalExpanded");
        } else {
            String incoming = getIntent().getStringExtra("pendingUpdate");
            if (incoming != null) pendingToken = incoming;
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy) load();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString("pluginId", selected);
        saved.putString("pending", pendingToken);
        saved.putString("setting", setting);
        saved.putBoolean("managementOnly", managementOnly);
        saved.putBoolean("returnToCaller", returnToCaller);
        saved.putBoolean("descriptionExpanded", descriptionExpanded);
        saved.putBoolean("managementExpanded", managementExpanded);
        saved.putBoolean("technicalExpanded", technicalExpanded);
    }

    @Override public void onBackPressed() {
        if (busy) return;
        if (pending != null) { cancelImport(); return; }
        if (!setting.isEmpty()) { setting = ""; render(); return; }
        if (showDownloads) { showDownloads = false; render(); return; }
        if (!selected.isEmpty()) {
            if (returnToCaller) { finish(); return; }
            resetDetailSections();
            selected = "";
            manifest = null;
            error = "";
            operationError = "";
            load();
        } else super.onBackPressed();
    }

    private void load() {
        busy = true;
        configurationLoaded = false;
        render();
        inBackground(DeviceQueues.GENERAL, () -> {
            Loaded result = new Loaded();
            result.plugins = repository.list();
            if (!pendingToken.isEmpty()) {
                try { result.pending = repository.pending(pendingToken); }
                catch (RuntimeException missing) { result.pendingError = message(missing); }
            }
            if (!selected.isEmpty()) {
                result.manifest = repository.load(selected);
                if (result.manifest == null) throw new java.io.IOException("插件已移除，请返回列表");
                result.config = result.manifest.runtime.equals(PluginManifest.LAUNCHER_RUNTIME)
                        ? new LinkedHashMap<>()
                        : repository.config(selected);
            }
            return result;
        }, (result, problem) -> {
            busy = false;
            if (problem != null) {
                error = message(problem);
                confirmed = false;
                render();
                return;
            }
            plugins = result.plugins;
            pending = result.pending;
            if (pending == null) pendingToken = "";
            manifest = result.manifest;
            if (nativeFeature() && !managementOnly && pending == null) {
                startActivity(settingsIntent(this, manifest));
                finish();
                return;
            }
            if (manifest != null) { config = result.config; configurationLoaded = true; }
            error = result.pendingError;
            render();
            if (manifest != null && pending == null) {
                if (launcher()) runLauncher(Action.REFRESH);
                else run("status", null);
            }
        });
    }

    private static final class Loaded {
        List<PluginManifest> plugins;
        PluginRepository.Pending pending;
        PluginManifest manifest;
        Map<String, String> config;
        String pendingError = "";
    }

    private void pick() {
        if (busy) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
        } catch (ActivityNotFoundException missing) {
            notice = "没有系统文件选择器，可从下载目录导入。";
            openDownloads();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            stage(() -> getContentResolver().openInputStream(uri));
        }
    }

    private interface Source { InputStream open() throws Exception; }

    private void stage(Source source) {
        if (busy) return;
        busy = true;
        error = "";
        notice = "";
        render();
        inBackground(DeviceQueues.GENERAL, () -> {
            if (pending != null) repository.cancel(pending);
            return repository.stage(source.open());
        }, (result, problem) -> {
            busy = false;
            pending = result;
            pendingToken = result == null ? "" : result.token;
            if (problem != null) error = message(problem);
            showDownloads = false;
            render();
        });
    }

    private void acceptImport() {
        if (busy || pending == null) return;
        PluginRepository.Pending accepted = pending;
        busy = true;
        error = "";
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> ExternalPlugins.importPackage(app, accepted), (installed, problem) -> {
            busy = false;
            if (problem != null) { error = message(problem); render(); return; }
            pending = null;
            pendingToken = "";
            resetDetailSections();
            selected = installed.id;
            manifest = installed;
            notice = installed.runtime.equals("launcher-v1")
                    ? "导入包已保存；已部署组件保持原状，可按需更新组件。"
                    : "插件包已保存，配置与数据保留。启用前请检查设置。";
            confirmed = false;
            enabled = null;
            if (nativeFeature()) {
                android.widget.Toast.makeText(this, notice, android.widget.Toast.LENGTH_LONG).show();
                Intent destination = settingsIntent(this, installed);
                boolean returnToList = !returnToCaller;
                if (returnToList && "capture".equals(installed.feature())) {
                    // 采集页是独立 Activity，保留列表作为返回目标。
                    selected = "";
                    manifest = null;
                    startActivity(destination);
                } else {
                    destination.putExtra("returnToPluginList", returnToList);
                    startActivity(destination);
                    finish();
                }
                return;
            }
            load();
        });
    }

    private void cancelImport() {
        if (busy || pending == null) return;
        PluginRepository.Pending cancelled = pending;
        busy = true;
        inBackground(DeviceQueues.GENERAL, () -> { repository.cancel(cancelled); return true; }, (ok, problem) -> {
            busy = false;
            if (problem != null) error = message(problem);
            else {
                pending = null; pendingToken = ""; error = "";
                load();
                return;
            }
            render();
        });
    }

    private void openDownloads() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE);
            return;
        }
        busy = true;
        render();
        inBackground(DeviceQueues.GENERAL, () -> {
            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] files = directory.listFiles();
            if (files == null) throw new java.io.IOException("无法读取下载目录");
            List<File> result = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".owo")) result.add(file);
            }
            java.util.Collections.sort(result, (a, b) -> a.getName().compareTo(b.getName()));
            return result;
        }, (result, problem) -> {
            busy = false;
            if (problem != null) error = message(problem);
            else { downloads = result; showDownloads = true; error = ""; }
            render();
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openDownloads();
            else { error = "未授予下载目录读取权限，可使用系统文件选择器。"; render(); }
        }
    }

    @Override public String moduleTitle() { return manifest == null ? "本地插件" : manifest.name; }

    @Override public State managementState() {
        if (launcher()) {
            EnumSet<Action> actions = launcherState == null ? EnumSet.of(Action.REFRESH) : launcherState.actions();
            if (nativeFeature()) {
                // 运行组件操作由完整功能页的原控制器处理；这里仅管理导入包。
                actions.retainAll(EnumSet.of(Action.REFRESH, Action.UNINSTALL));
            }
            return new State(true, launcherState == null ? null : launcherState.enabled, confirmed, busy,
                    launcherState == null ? "载荷已导入 · 部署状态待确认" : launcherState.text,
                    error, operationError, notice, launcherState == null ? ""
                            : launcherState.owner.equals("shared") ? "共享环境；导入包尚未部署。"
                            : launcherState.limitation(), actions);
        }
        EnumSet<Action> actions = EnumSet.of(Action.REFRESH);
        if (manifest != null) actions.addAll(EnumSet.of(Action.ENABLE, Action.DISABLE, Action.UNINSTALL));
        String status = !confirmed ? "状态待确认" : Boolean.TRUE.equals(enabled) ? "已启用" : "已停用";
        return new State(manifest != null, enabled, confirmed, busy, status, error, operationError,
                notice, "", actions, true);
    }

    @Override public String uninstallMessage() {
        if (launcher()) return launcherState != null && launcherState.installed && launcherState.owner.equals("own")
                ? "先卸载此功能的自有运行载荷，确认后移除导入包。其他插件、主题、卡片和媒体保留。"
                : "移除此导入包，保留数据；当前共享运行组件不受影响。";
        return "先运行插件的停用与卸载清理，确认停用后移除安装包。插件配置和数据保留；清理失败则保留安装包。";
    }

    @Override public String actionLabel(Action action) {
        if (action == Action.UNINSTALL) {
            if (!launcher()) return "卸载插件";
            return launcherState != null && launcherState.installed && "own".equals(launcherState.owner)
                    ? "卸载插件及组件" : "移除插件包";
        }
        if (launcher() && action == Action.UPDATE) return "更新运行组件";
        if (launcher() && action == Action.INSTALL) return "部署运行组件";
        return action.label;
    }

    @Override public void executeManagement(Action action) {
        if (launcher()) { runLauncher(action); return; }
        switch (action) {
            case REFRESH: run("status", null); break;
            case ENABLE: run("enable", null); break;
            case DISABLE: run("disable", null); break;
            case UNINSTALL: run("uninstall", null); break;
            default: break;
        }
    }

    private void runLauncher(Action action) {
        if (busy || !launcher()) return;
        String feature = manifest.feature();
        busy = true; error = ""; operationError = ""; notice = "";
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> LauncherPlugins.execute(app, feature, action), (result, problem) -> {
            busy = false; confirmed = problem == null;
            if (problem != null) {
                if (action == Action.REFRESH) error = message(problem);
                else operationError = message(problem);
            } else if (action == Action.UNINSTALL) {
                selected = ""; manifest = null; launcherState = null;
                notice = "本地插件包已移除，配置与数据保留。";
                load(); return;
            } else {
                launcherState = result;
                if (action != Action.REFRESH) notice = "操作已完成，状态已重新确认。";
            }
            render();
        });
    }

    private void run(String action, Map<String, String> next) {
        if (busy || manifest == null) return;
        String id = manifest.id;
        busy = true;
        error = "";
        operationError = "";
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> ExternalPlugins.run(app, id, action, next), (state, problem) -> {
            busy = false;
            confirmed = problem == null;
            enabled = problem == null ? state : null;
            notice = "";
            if (problem != null) {
                if (action.equals("status")) error = message(problem);
                else operationError = message(problem);
            } else if (action.equals("uninstall")) {
                selected = "";
                manifest = null;
                notice = "插件已卸载，配置与数据保留。";
                load();
                return;
            } else if (action.equals("configure")) {
                config = new LinkedHashMap<>(next);
                notice = "设置已保存。";
            } else if (!action.equals("status")) notice = state ? "插件已启用。" : "插件已停用。";
            render();
        });
    }

    private void render() {
        String page = pending != null ? "plugin-preview" : showDownloads ? "plugin-downloads"
                : manifest == null ? "plugin-list" : "plugin:" + manifest.id + ":" + setting;
        rebuild(page, () -> {
            if (manifest != null && pending == null && !showDownloads && setting.isEmpty()) {
                if (nativeFeature()) {
                    ui.header("管理与更新", false, this::onBackPressed, null);
                    ui.caption(manifest.name);
                    ModuleManagementUi.status(ui, this);
                    ui.value("插件包版本", manifest.versionName);
                    checkUpdates();
                    managementUi.actions(ui, this, this::render);
                    description();
                    technical();
                    return;
                }
                ModuleManagementUi.header(ui, this::onBackPressed, this);
                ModuleManagementUi.status(ui, this);
                if (manifest.restoreOnBoot && !Boolean.TRUE.equals(enabled)) {
                    String restoreError = PluginBootJob.failure(this, manifest.id);
                    if (!restoreError.isEmpty()) ui.note("自动恢复未完成：" + restoreError, "warning");
                }
                for (PluginManifest.Setting item : manifest.settings) {
                    ui.row(item.title, configurationLoaded ? item.options.get(config.get(item.key)) : "待读取", "", () -> {
                        if (canConfigure()) { setting = item.key; render(); }
                    }).setEnabled(canConfigure());
                }
                description();
                ui.disclosure("管理与更新", manifest.versionName, managementExpanded, () -> {
                    managementExpanded = !managementExpanded; render();
                });
                if (managementExpanded) {
                    checkUpdates();
                    managementUi.actions(ui, this, this::render);
                }
                technical();
                return;
            }
            ui.header(pending != null ? "确认导入" : showDownloads ? "下载目录" : settingTitle(),
                    false, this::onBackPressed, null);
            if (!error.isEmpty()) ui.note(error, "warning");
            if (!notice.isEmpty()) ui.note(notice);
            if (busy) ui.note("正在处理，请稍候…");
            if (pending != null) {
                ui.value("插件", pending.manifest.name);
                ui.value("版本", pending.manifest.versionName);
                ui.value("标识", pending.manifest.id);
                PluginManifest previous = null;
                String upgradeProblem = "";
                try {
                    previous = repository.load(pending.manifest.id);
                    if (previous != null) repository.upgradeFrom(pending);
                } catch (Exception problem) { upgradeProblem = message(problem); }
                if (previous != null) {
                    ui.value("当前版本", previous.versionName);
                    ui.note(pending.manifest.runtime.equals("launcher-v1")
                            ? "升级导入包，保留配置和已部署组件；安装后可另行更新组件。"
                            : "先停用此插件再升级。保留配置和数据；包校验或新版状态检查失败时恢复原包。");
                }
                if (!upgradeProblem.isEmpty()) ui.note(upgradeProblem, "warning");
                ui.note(pending.manifest.description);
                if (!pending.manifest.updateUrl.isEmpty()) ui.value("更新源", pending.manifest.updateUrl);
                ui.note(pending.manifest.runtime.equals("launcher-v1")
                        ? "此插件会在 Launcher 中运行代码，并可使用 root 部署。只导入你信任的代码；导入后仍需安装到自有运行环境，不能直接替换共享组件。"
                        : "本地插件未经官方审核。导入后，它的状态读取和管理脚本均可使用 root，能够修改系统及应用数据。仅导入你信任的作者或自己编写的插件。", "warning");
                ui.button(previous == null ? "信任并导入" : "信任并升级", true,
                        !busy && upgradeProblem.isEmpty(), this::acceptImport);
                ui.button("取消", false, !busy, this::cancelImport);
            } else if (showDownloads) {
                ui.note("将 .owo 插件包放入 Download 目录后在此选择。");
                for (File file : downloads) ui.row(file.getName(), "", "layers", () -> stage(() -> new FileInputStream(file)));
                if (downloads.isEmpty()) ui.note("下载目录中没有 .owo 插件包。");
                ui.button("刷新目录", false, !busy, this::openDownloads);
            } else if (manifest != null && !setting.isEmpty()) {
                for (PluginManifest.Setting item : manifest.settings) {
                    if (!item.key.equals(setting)) continue;
                    for (Map.Entry<String, String> option : item.options.entrySet()) {
                        ui.choice(option.getValue(), "", option.getKey().equals(config.get(item.key)), () -> {
                            if (!canConfigure()) return;
                            Map<String, String> next = new LinkedHashMap<>(config);
                            next.put(item.key, option.getKey());
                            setting = "";
                            run("configure", next);
                        }).setEnabled(canConfigure());
                    }
                }
            } else {
                for (PluginManifest item : plugins) ui.row(item.name, item.versionName, "layers", () -> {
                    if (busy) return;
                    if (PluginManifest.LAUNCHER_RUNTIME.equals(item.runtime) && !item.launcherExtension()) {
                        startActivity(settingsIntent(this, item).putExtra("returnToPluginList", true));
                        return;
                    }
                    resetDetailSections();
                    selected = item.id; confirmed = false; enabled = null; launcherState = null; notice = ""; load();
                });
                if (plugins.isEmpty()) ui.note("尚未导入本地插件。");
                ui.button("选择插件包", true, !busy, this::pick);
                ui.button("从下载目录导入", false, !busy, this::openDownloads);
                ui.button("刷新列表", false, !busy, this::load);
            }
        });
    }

    private String settingTitle() {
        if (manifest != null) for (PluginManifest.Setting item : manifest.settings) {
            if (item.key.equals(setting)) return item.title;
        }
        return "本地插件";
    }

    private void checkUpdates() {
        ui.button("检查插件包更新", false, !busy, () -> startActivity(
                new Intent(this, UpdateActivity.class).putExtra("pluginId", manifest.id)));
    }

    private void description() {
        ui.disclosure("插件说明", "", descriptionExpanded, () -> {
            descriptionExpanded = !descriptionExpanded; render();
        });
        if (descriptionExpanded) ui.caption(manifest.description);
    }

    private void technical() {
        ui.disclosure("技术信息", "", technicalExpanded, () -> {
            technicalExpanded = !technicalExpanded; render();
        });
        if (!technicalExpanded) return;
        ui.value("插件标识", manifest.id);
        ui.value("运行方式", manifest.runtime);
        ui.value("版本代码", String.valueOf(manifest.versionCode));
        if (manifest.restoreOnBoot) ui.caption("开启后记住状态，重启自动恢复；手动停用后不再恢复。");
        if (!manifest.updateUrl.isEmpty()) ui.value("更新源", manifest.updateUrl);
        if (launcher()) ui.caption("在 Launcher 内运行，启停与部署由管理器处理。");
        if (launcherState != null && !launcherState.limitation().isEmpty()) {
            ui.caption(launcherState.limitation());
        }
    }

    private void resetDetailSections() {
        descriptionExpanded = managementExpanded = technicalExpanded = false;
        managementUi = new ModuleManagementUi();
    }
}
