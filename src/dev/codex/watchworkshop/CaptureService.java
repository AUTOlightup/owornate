package dev.codex.watchworkshop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Choreographer;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 只在用户明确发起的截屏/录屏期间以前台服务运行；没有开机恢复，也不会自动录制。
 *
 * <p>所有命令排在本类的单线程队列上；等待录制结束另开一个线程、一条 ADB 连接阻塞读取，
 * 停止命令不受它影响。每个 onStartCommand 都重新确认前台通知，收尾时先移除通知再停服务。
 */
public final class CaptureService extends Service {
    private static final String CHANNEL = "capture";
    private static final int NOTIFICATION = 41;
    /** 录屏期间的亮屏锁上限：单段最长 180 秒再加保存时间。 */
    private static final long SCREEN_LOCK_MS = 190000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    /** 正在等待结束的录制 token；空串表示没有在观察。 */
    private volatile String observing = "";
    private boolean stopping;
    private int currentRequest;
    private int latestStartId;
    private PowerManager.WakeLock screen;

    static void request(Context context, String action) {
        context.startForegroundService(new Intent(context, CaptureService.class).putExtra("action", action));
    }

    /** 在正在结束的窗口的 onDestroy（主线程）里调用；再等两帧，画面里不会留下退出动画。 */
    static void screenshotAfterWindowClosed(Context context) {
        final Context app = context.getApplicationContext();
        Choreographer.getInstance().postFrameCallback(first ->
                Choreographer.getInstance().postFrameCallback(second -> request(app, "screenshot")));
    }

    static void startRecording(Context context, int bitrate) {
        context.startForegroundService(new Intent(context, CaptureService.class)
                .putExtra("action", "start")
                .putExtra("bitrate", bitrate));
    }

    /** 在与启停相同的队列上核对真实会话，不启动服务；finished 在主线程回调，成功时参数为 null。 */
    static void refreshStatus(Context context, Consumer<Exception> finished) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            Exception problem = null;
            try {
                CaptureManager.read(app);
            } catch (Exception e) {
                CaptureManager.fail(app, e);
                problem = e;
            }
            final Exception result = problem;
            new Handler(Looper.getMainLooper()).post(() -> finished.accept(result));
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "截屏与录屏", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("正在准备…", false));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        // 复用中的服务也要满足每一次 startForegroundService 的前台要求。
        startForeground(NOTIFICATION, notification("正在处理…", !observing.isEmpty()));
        if (intent == null) {
            finishService();
            return START_NOT_STICKY;
        }
        final String action = intent.getStringExtra("action");
        final int bitrate = intent.getIntExtra("bitrate", CaptureManager.DEFAULT_BITRATE);
        IO.execute(() -> {
            currentRequest = startId;
            perform(action, bitrate);
        });
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (screen != null && screen.isHeld()) screen.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // 队列上的动作
    // ------------------------------------------------------------------

    private void perform(String action, int bitrate) {
        if (destroyed) return;
        try {
            switch (action == null ? "" : action) {
                case "screenshot": screenshot(); break;
                case "record": toggleRecording(); break;
                case "start": start(bitrate); break;
                case "stop": stopRecording(); break;
                default: finishService(); break;
            }
        } catch (Exception error) {
            recover(error);
        }
    }

    private void screenshot() throws Exception {
        CaptureManager.Status result = CaptureManager.screenshot(this);
        toast("截屏已保存：" + result.path);
        observeOrFinish(CaptureManager.read(this));
    }

    /** 录屏按钮短按：正在录制就停止，否则打开录屏设置页。 */
    private void toggleRecording() throws Exception {
        CaptureManager.Status current = CaptureManager.read(this);
        if (current.active()) {
            stopRecording();
            return;
        }
        startActivity(CaptureEntryActivity.quickPage(this, CaptureOptionsActivity.class));
        finishService();
    }

    /** 设置页确认后开始；已经在录制时只接着观察那一段。 */
    private void start(int bitrate) throws Exception {
        CaptureManager.Status current = CaptureManager.read(this);
        if (current.active()) {
            observe(current);
            return;
        }
        CaptureManager.Status result = CaptureManager.start(this, bitrate);
        if (!result.active()) throw new IOException(result.error.isEmpty() ? "录制启动未确认" : result.error);
        observe(result);
    }

    private void stopRecording() throws Exception {
        stopping = true;
        show("正在保存录屏…", false);
        CaptureManager.Status result = CaptureManager.stop(this);
        if (result.active()) {
            observe(result);
            return;
        }
        complete(result);
    }

    /** 命令失败不代表已有的录制结束了：提示错误后再读一次，仍在录制就继续观察。 */
    private void recover(Exception error) {
        CaptureManager.fail(this, error);
        toast(WatchActivity.message(error));
        try {
            CaptureManager.Status current = CaptureManager.read(this);
            CaptureManager.fail(this, error);
            if (current.active()) {
                observe(current);
                return;
            }
        } catch (Exception ignored) {
            // 读不到就按结束处理，下次打开时再核对。
        }
        finishService();
    }

    private void observeOrFinish(CaptureManager.Status current) {
        if (current.active()) observe(current);
        else finishService();
    }

    /** 录制中：保持亮屏、通知里给出停止按钮，并对这个 token 只开一个结束等待。 */
    private void observe(CaptureManager.Status state) {
        keepScreenOn();
        show(progressText(state.phase), true);
        if (state.token.equals(observing)) return;
        observing = state.token;
        awaitEnd(state.token);
    }

    private static String progressText(String phase) {
        if ("stopping".equals(phase)) return "正在保存录屏…";
        if ("starting".equals(phase)) return "正在准备录屏…";
        return "录屏中 · 单段最长 3 分钟";
    }

    /** 另开线程在独立的 ADB 连接上阻塞等待结束；结果回到队列上处理，停止命令不受影响。 */
    private void awaitEnd(String token) {
        new Thread(() -> {
            try {
                CaptureManager.Status result = CaptureManager.awaitEnd(getApplicationContext(), token);
                IO.execute(() -> {
                    if (destroyed || !token.equals(observing)) return;
                    CaptureManager.accept(this, result);
                    if (result.active()) show("状态读取未完成，请点停止重试", true);
                    else complete(result);
                });
            } catch (Exception error) {
                if (!destroyed && token.equals(observing)) show("录制状态读取失败，可点停止重试", true);
            }
        }, "capture-completion").start();
    }

    private void complete(CaptureManager.Status state) {
        if (!state.error.isEmpty()) toast(state.error);
        else if (!state.path.isEmpty()) toast("录屏已保存：" + state.path);
        else if (stopping) toast("当前没有正在录制的视频");
        finishService();
    }

    // ------------------------------------------------------------------
    // 前台通知、亮屏锁与收尾（主线程）
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void keepScreenOn() {
        if (screen == null) {
            screen = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "OWOrnate:capture");
            screen.setReferenceCounted(false);
        }
        if (!screen.isHeld()) screen.acquire(SCREEN_LOCK_MS);
    }

    private Notification notification(String text, boolean allowStop) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_capture_record)
                .setContentTitle("OWOrnate 截屏与录屏")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (allowStop) {
            PendingIntent stop = PendingIntent.getService(this, 0,
                    new Intent(this, CaptureService.class).putExtra("action", "stop"),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_capture_stop, "停止", stop).build());
        }
        return builder.build();
    }

    private void show(String text, boolean stop) {
        main.post(() -> {
            if (destroyed) return;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION, notification(text, stop));
        });
    }

    private void toast(String text) {
        main.post(() -> {
            if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        });
    }

    /** 只结束最新那一次请求：期间又来了新请求时保持运行。先移除前台通知，必要时显式 stopService。 */
    private void finishService() {
        final int request = currentRequest;
        main.post(() -> {
            if (destroyed || latestStartId != request) return;
            stopForeground(true);
            boolean stopped = stopSelfResult(request);
            if (!stopped) stopped = stopService(new Intent(this, CaptureService.class));
            Log.i("OWCapture", "finish request=" + request + " stopped=" + stopped);
        });
    }
}
