package dev.codex.watchworkshop;

/** Deployment/enabling of the OWOrnate module, distinct from a recording session. */
final class CaptureModuleState {
    final String kind, owner;

    CaptureModuleState(String kind) {
        this(kind, "");
    }

    CaptureModuleState(String kind, String owner) {
        if (!"available".equals(kind)
                && !"ready".equals(kind)
                && !"disabled".equals(kind)
                && !"installed".equals(kind)
                && !"update".equals(kind)
                && !"setup".equals(kind)
                && !"conflict".equals(kind))
            throw new IllegalArgumentException("capture module state");
        this.kind = kind;
        this.owner = owner == null ? "" : owner;
    }

    boolean installed() {
        return "ready".equals(kind) || "installed".equals(kind) || "disabled".equals(kind)
                || "update".equals(kind);
    }

    boolean enabled() {
        return "ready".equals(kind) || "installed".equals(kind);
    }

    /** update 状态只说明文件需要刷新，不能把未知的启停值当成停用。 */
    boolean enabledKnown() {
        return "ready".equals(kind) || "installed".equals(kind) || "disabled".equals(kind);
    }

    boolean live() {
        return "ready".equals(kind);
    }

    boolean removable() {
        return installed() && "own".equals(owner);
    }

    String message() {
        if ("ready".equals(kind)) return "已安装 · 已启用";
        if ("disabled".equals(kind)) return "已安装 · 已停用";
        if ("installed".equals(kind)) return "已安装 · 加载未确认";
        if ("update".equals(kind)) return "已安装 · 可更新";
        if ("setup".equals(kind)) return "首次使用需要部署管理器";
        if ("conflict".equals(kind)) return "已有运行组件不兼容";
        return "未安装 · 可在手表安装";
    }
}
