package dev.codex.watchworkshop;

import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * 管理器主界面：页面栈、右滑逐级返回、页头、首页和“发现优化”。
 *
 * <p>各功能的页面和状态在 {@link MonetPages}、{@link WidgetPages}、{@link SystemPages} 里；
 * 这里只负责跳转。首页直接列出已安装的功能，“发现优化”只在还有没装的功能时出现。
 */
public final class MainActivity extends WatchActivity {
    /** 只读状态多久内算新鲜：回到首页或从设置页返回时，这段时间内不重复读取。 */
    static final long FRESH_MS = 30_000;
    private static final HashSet<String> PAGES = new HashSet<>(Arrays.asList(
            "welcome", "home", "discover", "settings", "check", "guide", "technical", "licenses",
            "detail", "theme", "theme-more", "theme-restore", "modes", "colors", "install", "uninstall",
            "progress", "result", "export", "wdetail", "wplan", "widgets"));

    final ArrayDeque<String> stack = new ArrayDeque<>();
    String page = "home";
    SharedPreferences prefs;
    MonetPages monet;
    WidgetPages widgets;
    SystemPages system;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("workshop", MODE_PRIVATE);
        monet = new MonetPages(this);
        widgets = new WidgetPages(this);
        system = new SystemPages(this);
        if (saved != null) {
            page = saved.getString("page", "home");
            ArrayList<String> restored = saved.getStringArrayList("stack");
            if (restored != null) stack.addAll(restored);
            remember(page, saved.getInt("scroll", 0));
        } else if (!prefs.getBoolean("welcomed", false)) {
            page = "welcome";
        }
        stack.retainAll(PAGES);
        // 进行中的操作不会跨进程延续；结果页只在有记录时恢复。
        if (!PAGES.contains(page) || page.equals("progress") || (page.equals("result") && !monet.hasResult())) {
            page = "home";
            stack.clear();
        }
        render();
        system.prepareKeys();
        RuntimeSupport.ensureAsync(this, false);
    }

    @Override protected void onResume() {
        super.onResume();
        // 从小组件设置页回来：卡片摘要来自本地数据，直接重画。
        if (page.equals("widgets")) render();
        refresh(false);
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("page", page);
        b.putStringArrayList("stack", new ArrayList<>(stack));
        b.putInt("scroll", scrollY());
    }

    /** 读两类只读状态；force=false 时距上次成功读取不到 {@link #FRESH_MS} 就跳过。 */
    void refresh(boolean force) {
        monet.refresh(force);
        widgets.refresh(force);
    }

    boolean working() {
        return monet.working() || widgets.busy();
    }

    @Override public void onBackPressed() {
        if (page.equals("progress") || widgets.blocksBack(page)) return;
        if (page.equals("result")) {
            monet.finishResult();
            return;
        }
        if (stack.isEmpty()) {
            if (page.equals("home") || page.equals("welcome")) finish();
            else returnHome();
            return;
        }
        page = stack.pop();
        render();
        if (page.equals("home")) refresh(false);
    }

    void go(String target) {
        if (page.equals("progress")) return;
        if (stack.contains(target)) {
            while (!stack.pop().equals(target)) { }
        } else if (!page.equals(target)) {
            stack.push(page.equals("welcome") ? "home" : page);
        }
        page = target;
        render();
        if (target.equals("theme")) monet.enterTheme();
    }

    /** 直接落到 target，并给它补上合理的上一级（结果页结束时用）。 */
    void jump(String target) {
        if (stack.contains(target)) {
            while (!stack.pop().equals(target)) { }
        } else {
            stack.clear();
            if (!target.equals("home")) stack.push("home");
            if (target.equals("detail")) stack.push("discover");
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
        page = stack.isEmpty() ? "home" : stack.pop();
        render();
    }

    /** 操作页（进度、结果）原地替换当前页，不进栈。 */
    void replace(String target) {
        page = target;
        remember(target, 0);
        render();
    }

    void returnHome() {
        stack.clear();
        page = "home";
        remember("home", 0);
        render();
        refresh(false);
    }

    void render() {
        rebuild(page, () -> {
            boolean root = page.equals("home") || page.equals("welcome") || page.equals("progress");
            if (page.equals("theme")) monet.themeHeader();
            else ui.header(title(), root, root ? null : this::onBackPressed,
                    page.equals("home") ? () -> go("settings") : null);
            if (page.equals("home")) home();
            else if (page.equals("discover")) discover();
            else if (!monet.render(page) && !widgets.render(page) && !system.render(page)) home();
        });
    }

    private String title() {
        if (page.equals("home") || page.equals("welcome")) return "OWOrnate";
        if (page.equals("discover")) return "发现优化";
        String title = monet.title(page);
        if (title == null) title = widgets.title(page);
        if (title == null) title = system.title(page);
        return title == null ? "OWOrnate" : title;
    }

    /** 首页：概览卡，下面直接是已安装的功能；还有没装的功能时才出现“发现优化”。 */
    private void home() {
        boolean monetOn = monet.installed(), widgetsOn = widgets.on();
        Integer count = monet.known() && widgets.known() ? (monetOn ? 1 : 0) + (widgetsOn ? 1 : 0) : null;
        String status;
        if (count == null) status = monet.refreshing() || widgets.refreshing() ? "正在确认状态" : "状态待确认";
        else if (count == 0) status = "尚未安装任何功能";
        else status = (widgetsOn ? "小组件已接入" : "") + (widgetsOn && monetOn ? " · " : "") + (monetOn ? "莫奈取色已安装" : "");
        ui.summary(count, "项功能，已纳入管理", status);
        if (widgetsOn) widgets.homeRow();
        if (monetOn) monet.homeRow();
        if (monet.failed()) {
            ui.note(monet.error(), "warning");
            ui.button("重新读取状态", false, !working(), () -> refresh(true));
        }
        if (!(monetOn && widgetsOn)) {
            int left = 2 - (monetOn ? 1 : 0) - (widgetsOn ? 1 : 0);
            ui.row("发现优化", count == null ? "浏览可安装的功能" : "还有 " + left + " 项可安装", "compass", () -> go("discover"));
        }
    }

    private void discover() {
        List<ModuleCatalog.Entry> catalog = ModuleCatalog.sorted(Arrays.asList(
                new ModuleCatalog.Entry("monet", "莫奈取色", monet.installed()),
                new ModuleCatalog.Entry("widgets", "负一屏小组件", widgets.on())));
        for (ModuleCatalog.Entry item : catalog) {
            if ("widgets".equals(item.id)) {
                ui.row(item.title, item.installed ? "已接入 · 信息、倒计时与课表" : "内置 · 无需联网 · 信息、倒计时与课表",
                        "layers", () -> go("wdetail"));
            } else {
                ui.row(item.title, monet.discoverLine(), "palette", () -> go("detail"));
            }
        }
    }
}
