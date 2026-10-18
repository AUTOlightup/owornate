package dev.codex.watchworkshop;

/** Deployment/enabling of the OWOrnate module, distinct from a recording session. */
final class CaptureModuleState {
    final String kind;
    CaptureModuleState(String kind){
        if(!"available".equals(kind)&&!"ready".equals(kind)&&!"disabled".equals(kind)&&!"installed".equals(kind))
            throw new IllegalArgumentException("capture module state");
        this.kind=kind;
    }
    boolean installed(){return !"available".equals(kind);}
    boolean enabled(){return "ready".equals(kind);}
    String message(){
        if("ready".equals(kind))return "已安装 · 已启用";
        if("disabled".equals(kind))return "已安装 · 已停用";
        if("installed".equals(kind))return "已安装 · 加载未确认";
        return "未安装 · 可在手表安装";
    }
}
