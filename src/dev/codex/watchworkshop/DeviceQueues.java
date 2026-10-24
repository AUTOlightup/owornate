package dev.codex.watchworkshop;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 管理器界面的两条后台队列。每条队列只有一个线程，同一条队列上的设备操作按提交顺序依次执行；
 * 后台任务不访问任何 View，结果经 {@link WatchActivity#inBackground} 回到主线程。
 *
 * <p>两条队列互不排队：小组件接入可能要重载表盘十几秒，不能挡住莫奈或运行环境的读取。
 * 真正改动共享组件的操作另有各 Manager 的类锁串行，队列只决定界面上的先后。
 */
final class DeviceQueues {
    /** 莫奈读写、运行环境检测、本机密钥准备、截屏录屏模块管理和信息卡片的状态读取。 */
    static final ExecutorService GENERAL = Executors.newSingleThreadExecutor();

    /** 负一屏小组件：接入、状态读取、后台更新修复和隐藏小布助手。 */
    static final ExecutorService WIDGETS = Executors.newSingleThreadExecutor();

    private DeviceQueues() {}
}
