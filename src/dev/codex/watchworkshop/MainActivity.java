package dev.codex.watchworkshop;

import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理器主界面：页面栈、右滑逐级返回、页头、首页和“发现优化”。
 *
 * <p>各功能的页面和状态在 {@link MonetPages}、{@link WidgetPages}、{@link CapturePages}、{@link SystemPages} 里；
 * 这里只负责跳转。首页直接列出已安装的功能，“发现优化”入口常驻，用于查看接入状态或安装未装功能。
 */
public final class MainActivity extends WatchActivity {
    /** 只读状态多久内算新鲜：回到首页或从设置页返回时，这段时间内不重复读取。 */
    static final long FRESH_MS = 30_000;
    private final PageRegistry pages = new PageRegistry();
    /** 发现页/错误提示沿用目录顺序，首页沿用自己的展示顺序。 */
    private final Map<String, ModuleItem> modules = new LinkedHashMap<>();
    private List<ModuleItem> homeModules;
    private List<PluginManifest> localPlugins = new ArrayList<>();
    private boolean pluginsLoaded, pluginsLoading;
    private String pluginsError = "";

    private static final class ModuleItem {
        final ModuleStatusView status;
        final String title, icon, errorPrefix;
        final Runnable openHome, openDiscover;

        ModuleItem(ModuleStatusView status, String title, String icon,
                String errorPrefix, Runnable openHome, Runnable openDiscover) {
            this.status = status;
            this.title = title;
            this.icon = icon;
            this.errorPrefix = errorPrefix;
            this.openHome = openHome;
            this.openDiscover = openDiscover;
        }
    }

    final ArrayDeque<String> stack = new ArrayDeque<>();
    String page = PageIds.HOME;
    private String returnPluginListPage = "";
    SharedPreferences prefs;
    MonetPages monet;
    WidgetPages widgets;
    CapturePages capture;
    SystemPages system;
    /** 任一入口（其他页面、卡片设置页）发布了新的观察结果：三个模块重新投影后重画。 */
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeChanged = (store, key) -> {
        if (destroyed()) return;
        syncModules();
        render();
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("workshop", MODE_PRIVATE);
        monet = new MonetPages(this);
        widgets = new WidgetPages(this);
        capture = new CapturePages(this);
        system = new SystemPages(this);
        registerModules();
        pages.add(PageIds.HOME, "OWOrnate", this::home);
        pages.add(PageIds.DISCOVER, "发现优化", this::discover);
        monet.registerPages(pages);
        widgets.registerPages(pages);
        system.registerPages(pages);
        observeWhileStarted(RuntimeStateStore.preferences(this), runtimeChanged);
        if (saved != null) {
            page = saved.getString("page", PageIds.HOME);
            returnPluginListPage = saved.getString("returnPluginListPage", "");
            ArrayList<String> restored = saved.getStringArrayList("stack");
            if (restored != null) stack.addAll(restored);
            remember(page, saved.getInt("scroll", 0));
        } else if (!prefs.getBoolean("welcomed", false)) {
            page = PageIds.WELCOME;
        }
        stack.retainAll(pages.ids());
        // 进行中的操作不会跨进程延续；结果页只在有记录时恢复。
        if (!pages.contains(page)
                || page.equals(PageIds.PROGRESS)
                || (page.equals(PageIds.RESULT) && !monet.hasResult())) {
            page = PageIds.HOME;
            stack.clear();
        }
        render();
        system.prepareKeys();
        openPluginSettings(getIntent());
        SetupCheck.requested(this, getIntent());
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openPluginSettings(intent);
        SetupCheck.requested(this, intent);
    }

    private void openPluginSettings(android.content.Intent intent) {
        String feature = intent.getStringExtra("pluginFeature");
        String target = "monet".equals(feature) ? PageIds.THEME
                : "widgets".equals(feature) ? PageIds.WIDGETS : "";
        if (!target.isEmpty()) {
            if (intent.getBooleanExtra("returnToPluginList", false)) returnPluginListPage = target;
            else if (!target.equals(page)) returnPluginListPage = "";
        }
        openFeatureSettings(feature);
    }

    /** 官方功能始终进入原完整 native 设置；其他插件仍由 PluginActivity 展示。 */
    private boolean openFeatureSettings(String feature) {
        if ("monet".equals(feature)) {
            go(PageIds.THEME);
            return true;
        }
        if ("widgets".equals(feature)) {
            go(PageIds.WIDGETS);
            return true;
        }
        if ("capture".equals(feature)) {
            startActivity(new android.content.Intent(this, CaptureSetupActivity.class));
            return true;
        }
        return false;
    }

    private void registerModules() {
        ModuleItem monetItem = new ModuleItem(monet, "莫奈取色", "palette", "",
                () -> go(monet.installed() ? PageIds.THEME : PageIds.DETAIL),
                () -> go(PageIds.DETAIL));
        ModuleItem widgetItem = new ModuleItem(widgets, "负一屏小组件", "layers", "小组件：",
                () -> go(PageIds.WIDGETS), () -> go(PageIds.WIDGET_DETAIL));
        ModuleItem captureItem = new ModuleItem(capture, "截屏与录屏", "scan", "截屏与录屏：",
                capture::open, capture::open);
        modules.put("monet", monetItem);
        modules.put("widgets", widgetItem);
        modules.put("capture", captureItem);
        homeModules = Arrays.asList(widgetItem, monetItem, captureItem);
    }

    @Override protected void onResume() {
        super.onResume();
        // 其他入口也会写同一份存储：先接住它们的结果，再按新鲜度决定要不要读。
        syncModules();
        render();
        refresh(false);
        loadPlugins();
    }

    private void loadPlugins() {
        if (pluginsLoading) return;
        pluginsLoading = true;
        inBackground(DeviceQueues.GENERAL, () -> ExternalPlugins.repository(getApplicationContext()).list(),
                (result, problem) -> {
            pluginsLoading = false;
            pluginsLoaded = problem == null;
            pluginsError = problem == null ? "" : message(problem);
            if (problem == null) localPlugins = result;
            render();
        });
    }

    private void openPlugin(String id) {
        startActivity(new android.content.Intent(this, PluginActivity.class).putExtra("pluginId", id));
    }

    /** 官方功能导入包进入对应 native 设置；shell 和第三方 launcher 插件仍走插件详情。 */
    private void openPlugin(PluginManifest plugin) {
        if (plugin == null) {
            openPlugin("");
            return;
        }
        if (openFeatureSettings(plugin.feature())) return;
        openPlugin(plugin.id);
    }

    private PluginManifest importedPlugin(String feature) {
        if (feature == null || feature.isEmpty()) return null;
        for (PluginManifest plugin : localPlugins) {
            if (feature.equals(plugin.feature())) return plugin;
        }
        return null;
    }

    /** 已导入包对应元数据页；无包返回 null，页面只保留原生管理动作。 */
    Runnable pluginPackagePage(String feature) {
        PluginManifest plugin = importedPlugin(feature);
        if (plugin == null) return null;
        return () -> {
            if (working()) return;
            startActivity(PluginActivity.managementIntent(this, plugin.id));
        };
    }

    private PluginManifest importedFeature(ModuleItem item) {
        for (PluginManifest plugin : localPlugins) {
            if (!plugin.feature().isEmpty() && modules.get(plugin.feature()) == item) return plugin;
        }
        return null;
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("page", page);
        b.putString("returnPluginListPage", returnPluginListPage);
        b.putStringArrayList("stack", new ArrayList<>(stack));
        b.putInt("scroll", scrollY());
    }

    private void syncModules() {
        capture.sync();
        monet.sync();
        widgets.sync();
    }

    /** 读三类只读状态；force=false 时距上次成功读取不到 {@link #FRESH_MS} 就跳过。 */
    void refresh(boolean force) {
        // 顺序固定：莫奈开始读取后会计入 working()，放在最后才不会让另外两个跳过。
        capture.refresh(force);
        widgets.refresh(force);
        monet.refresh(force);
    }

    boolean working() {
        return monet.working() || widgets.busy() || system.busy();
    }

    boolean anyRefreshing() {
        for (ModuleItem item : modules.values()) {
            if (item.status.refreshing()) return true;
        }
        return false;
    }

    boolean anyReadFailed() {
        for (ModuleItem item : modules.values()) {
            if (item.status.failed()) return true;
        }
        return false;
    }

    /** 各模块本次读取失败的原因，一条一张说明卡。tone 为空时用中性色。 */
    void readErrorNotes(String tone) {
        for (ModuleItem item : modules.values()) {
            if (item.status.failed()) ui.note(item.errorPrefix + item.status.error(), tone);
        }
    }

    @Override public void onBackPressed() {
        if (page.equals(PageIds.PROGRESS) || widgets.blocksBack(page)) return;
        if (page.equals(PageIds.RESULT)) {
            monet.finishResult();
            return;
        }
        boolean returnToList = page.equals(returnPluginListPage);
        if (returnToList) returnPluginListPage = "";
        if (stack.isEmpty()) {
            if (page.equals(PageIds.HOME) || page.equals(PageIds.WELCOME)) finish();
            else returnHome();
            if (returnToList) startActivity(new android.content.Intent(this, PluginActivity.class));
            return;
        }
        page = stack.pop();
        render();
        if (page.equals(PageIds.HOME)) refresh(false);
        if (returnToList) startActivity(new android.content.Intent(this, PluginActivity.class));
    }

    void go(String target) {
        if (page.equals(PageIds.PROGRESS)) return;
        if (stack.contains(target)) {
            while (!stack.pop().equals(target)) { }
        } else if (!page.equals(target)) {
            stack.push(page.equals(PageIds.WELCOME) ? PageIds.HOME : page);
        }
        page = target;
        render();
        if (target.equals(PageIds.THEME)) monet.enterTheme();
    }

    /** 直接落到 target，并给它补上合理的上一级（结果页结束时用）。 */
    void jump(String target) {
        if (stack.contains(target)) {
            while (!stack.pop().equals(target)) { }
        } else {
            stack.clear();
            if (!target.equals(PageIds.HOME)) stack.push(PageIds.HOME);
            if (target.equals(PageIds.DETAIL)) stack.push(PageIds.DISCOVER);
        }
        page = target;
        remember(target, 0);
        render();
    }

    /** 当前页已不成立（比如功能被卸载）时退回上一级。 */
    void leave(String target) {
        if (!page.equals(target)) {
            stack.remove(target);
            return;
        }
        page = stack.isEmpty() ? PageIds.HOME : stack.pop();
        render();
    }

    /** 操作页（进度、结果）原地替换当前页，不进栈。 */
    void replace(String target) {
        page = target;
        remember(target, 0);
        render();
    }

    void returnHome() {
        returnPluginListPage = "";
        stack.clear();
        page = PageIds.HOME;
        remember(PageIds.HOME, 0);
        render();
        refresh(false);
    }

    void render() {
        rebuild(page, () -> {
            boolean root = page.equals(PageIds.HOME) || page.equals(PageIds.WELCOME) || page.equals(PageIds.PROGRESS);
            ModuleManagement management = pages.management(page);
            if (management != null) ModuleManagementUi.header(ui, this::onBackPressed, management);
            else ui.header(title(), root, root ? null : this::onBackPressed,
                    page.equals(PageIds.HOME) ? () -> go(PageIds.SETTINGS) : null);
            if (!pages.render(page)) home();
        });
    }

    private String title() {
        String title = pages.title(page);
        return title == null ? "OWOrnate" : title;
    }

    /** 首页列出已安装功能并保留检查入口；「发现优化」入口常驻，可安装数量仅统计确认可装的功能。 */
    private void home() {
        boolean allKnown = pluginsLoaded;
        int installed = 0;
        for (PluginManifest plugin : localPlugins) {
            ModuleItem nativeModule = modules.get(plugin.feature());
            if (nativeModule == null || !nativeModule.status.installed()) installed++;
        }
        for (ModuleItem item : modules.values()) {
            allKnown &= item.status.known();
            if (item.status.installed()) installed++;
        }
        Integer count = allKnown ? Integer.valueOf(installed) : null;
        ui.summary(count, "项功能", "");
        for (ModuleItem item : modules.values()) {
            String warning = item.status.homeWarning();
            if (!warning.isEmpty()) ui.note(warning, "warning");
        }
        for (ModuleItem item : homeModules) {
            if (item.status.showOnHome()) {
                PluginManifest imported = importedFeature(item);
                ui.row(item.title, "",
                        item.icon, imported == null ? item.openHome : () -> openPlugin(imported));
            }
        }
        readErrorNotes("warning");
        for (PluginManifest plugin : localPlugins) {
            ModuleItem nativeModule = modules.get(plugin.feature());
            if (nativeModule != null && nativeModule.status.showOnHome()) continue;
            ui.row(plugin.name, "", "layers", () -> openPlugin(plugin));
        }
        if (!pluginsError.isEmpty()) {
            ui.note("本地插件列表读取失败：" + pluginsError, "warning");
            ui.button("重新读取插件列表", false, !pluginsLoading, this::loadPlugins);
        }
        if (anyReadFailed()) ui.button("重新读取状态", false, !working(), () -> refresh(true));
        ui.row("发现优化", "", "compass", () -> go(PageIds.DISCOVER));
    }

    private void discover() {
        List<ModuleCatalog.Entry> catalog = new ArrayList<>();
        for (Map.Entry<String, ModuleItem> entry : modules.entrySet()) {
            ModuleItem item = entry.getValue();
            catalog.add(new ModuleCatalog.Entry(entry.getKey(), item.title, item.status.installed()));
        }
        List<ModuleCatalog.Entry> sorted = ModuleCatalog.sorted(catalog);
        ui.title("未安装");
        boolean hasUninstalled = false;
        for (ModuleCatalog.Entry entry : sorted) {
            if (!entry.installed) {
                discoverRow(entry.id);
                hasUninstalled = true;
            }
        }
        if (!hasUninstalled) ui.caption("暂无");

        ui.title("已安装");
        ui.highlightedRow("本地插件", "layers", () -> openPlugin(""));
        for (ModuleCatalog.Entry entry : sorted) {
            if (entry.installed) discoverRow(entry.id);
        }
    }

    private void discoverRow(String id) {
        ModuleItem item = modules.get(id);
        PluginManifest imported = importedFeature(item);
        String subtitle = item.status.installed() && imported != null ? imported.versionName : "";
        ui.row(item.title, subtitle, item.icon,
                imported == null ? item.openDiscover : () -> openPlugin(imported));
    }
}
