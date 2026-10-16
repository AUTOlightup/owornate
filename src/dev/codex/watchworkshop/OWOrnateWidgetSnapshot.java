package dev.codex.watchworkshop;

/** Confirmed observation, never the theme editor's draft. No Android dependencies. */
final class OWOrnateWidgetSnapshot {
    final String kind;
    final boolean activeKnown, active;
    final ThemeConfig theme;
    final long confirmedAt;

    OWOrnateWidgetSnapshot(String kind, boolean known, boolean active, ThemeConfig theme, long at) {
        this.kind=kind==null?"unknown":kind;
        this.activeKnown=known;
        this.active=known&&active;
        this.theme=known&&active&&installed()?theme:null;
        this.confirmedAt=known?Math.max(0,at):0;
    }
    boolean installed(){return "installed".equals(kind)||"shared".equals(kind);}
    String statusText(){
        if(!activeKnown)return "莫奈 · 状态待确认";
        if(installed())return active?"莫奈 · 已启用":"莫奈 · 已停用";
        if("available".equals(kind))return "莫奈 · 未安装";
        if("setup".equals(kind))return "莫奈 · 等待连接";
        return "莫奈 · 环境待检查";
    }
    String themeText(){
        if(!activeKnown)return "打开组件读取状态";
        if(!installed()||!active)return "打开 OWOrnate 管理";
        return theme==null?"配色待确认":theme.modeLabel()+" · "+(theme.corners?"连续圆角":"原生圆角");
    }
}
