package dev.codex.watchworkshop;

/** 主线程上的莫奈操作阶段；主题队列的运行状态仍由 LatestThemeQueue 提供。 */
final class MonetOperationState {
    enum Phase {
        IDLE,
        REFRESHING,
        READING_THEME,
        ROLLING_BACK,
        WRITING_THEME,
        EXPORTING,
        INSTALLING("install"),
        UPDATING("update"),
        REPAIRING("repair"),
        ENABLING("enable"),
        DISABLING("disable"),
        UNINSTALLING("uninstall");

        final String command;

        Phase() {
            this(null);
        }

        Phase(String command) {
            this.command = command;
        }
    }

    private Phase phase = Phase.IDLE;

    Phase phase() {
        return phase;
    }

    boolean working(boolean writesRunning) {
        return phase != Phase.IDLE || writesRunning;
    }

    boolean begin(Phase next, boolean writesRunning) {
        if (working(writesRunning)) return false;
        phase = next;
        return true;
    }

    void finish(Phase completed) {
        if (phase == completed) phase = Phase.IDLE;
    }

    /** submit 成功后调用；继续选色只替换队列中的待写项，不另起设备操作。 */
    void themeSubmitted() {
        phase = Phase.WRITING_THEME;
    }

    /** 后台结束到 UI 回调之间仍保持忙碌；旧 idle 回调不能结束新一轮写入或其他操作。 */
    void writesFinished(boolean writesRunning) {
        if (!writesRunning) finish(Phase.WRITING_THEME);
    }

    boolean editable(boolean writesRunning) {
        return phase == Phase.IDLE || (phase == Phase.WRITING_THEME && writesRunning);
    }

    boolean refreshing() {
        return phase == Phase.REFRESHING;
    }

    boolean moduleBusy() {
        return phase.command != null;
    }

    boolean themeBusy() {
        return phase == Phase.READING_THEME
                || phase == Phase.ROLLING_BACK
                || phase == Phase.WRITING_THEME;
    }

}
