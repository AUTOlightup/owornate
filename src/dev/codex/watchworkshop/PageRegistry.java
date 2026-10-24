package dev.codex.watchworkshop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 每个页面一次注册 id、标题和渲染入口，导航恢复也使用同一份页面集合。 */
final class PageRegistry {
    private static final class Page {
        final Supplier<String> title;
        final Runnable render;
        final ModuleManagement management;

        Page(Supplier<String> title, Runnable render, ModuleManagement management) {
            this.title = title;
            this.render = render;
            this.management = management;
        }
    }

    private final Map<String, Page> pages = new LinkedHashMap<>();

    void add(String id, String title, Runnable render) {
        add(id, () -> title, render);
    }

    void add(String id, Supplier<String> title, Runnable render) {
        pages.put(id, new Page(title, render, null));
    }

    void addManaged(String id, ModuleManagement module, Runnable render) {
        pages.put(id, new Page(module::moduleTitle, render, module));
    }

    ModuleManagement management(String id) {
        Page page = pages.get(id);
        return page == null ? null : page.management;
    }

    Set<String> ids() {
        return pages.keySet();
    }

    boolean contains(String id) {
        return pages.containsKey(id);
    }

    String title(String id) {
        Page page = pages.get(id);
        return page == null ? null : page.title.get();
    }

    boolean render(String id) {
        Page page = pages.get(id);
        if (page == null) return false;
        page.render.run();
        return true;
    }
}
