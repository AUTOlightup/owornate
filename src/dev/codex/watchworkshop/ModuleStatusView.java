package dev.codex.watchworkshop;

/** 首页与发现页读取的模块投影；不执行设备读取或修改，各模块保留自己的状态文案。 */
interface ModuleStatusView {
    boolean known();

    boolean installed();

    boolean available();

    boolean refreshing();

    boolean failed();

    String error();

    String statusText();

    String discoverLine();

    default boolean showOnHome() {
        return installed();
    }

    default String homeLine() {
        return statusText();
    }

    /** 模块特有的概览提示，优先于数量/已安装摘要，低于读取失败。 */
    default String homeWarning() {
        return "";
    }
}
