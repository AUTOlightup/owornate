package dev.codex.watchworkshop;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 倒计时卡片的“纪念日”模式：只读地同步系统快应用纪念日的数据。
 *
 * <p>读取：走本机 root 通道，用固定命令把纪念日的 MMKV 文件和校验文件以 base64 取回，
 * 在这里校验 CRC 后解析（{@link AnniversaryData}）。从不写纪念日的任何文件。一次约 0.3 秒。
 *
 * <p>什么时候读：只在纪念日模式下。进程启动、午夜换日、打开倒计时设置页；点卡片跳进纪念日后
 * 的几分钟里再看几次；以及亮屏时，如果系统使用记录显示快应用引擎在上次读取之后到过前台。
 * 最后这条要“使用情况访问”权限，切到纪念日模式时用 root 授予、切回时撤回，只影响本应用。
 * 纪念日每次打开都会原样重写一遍数据，所以变没变按内容比较，不看文件时间。
 */
final class AnniversarySync {
    static final String ENGINE = "com.heytap.wearable.instant";
    static final String APP = "com.nearme.watch.datecalculate";
    private static final String TAG = "AnniversarySync";
    private static final String DIR = "/data/data/" + ENGINE + "/app_database/" + APP;
    private static final String SELF = "dev.codex.watchworkshop";
    /** 固定命令：找出唯一一份数据文件，连同校验文件头一起以 base64 输出。 */
    private static final String READ = "d=" + DIR + "; [ -d \"$d\" ] || { echo MISSING; exit 0; }; "
            + "f=; n=0; for x in \"$d\"/*; do case \"$x\" in *.crc) continue;; esac; "
            + "[ -f \"$x\" ] && [ -f \"$x.crc\" ] || continue; f=$x; n=$((n+1)); done; "
            + "[ \"$n\" = 0 ] && { echo MISSING; exit 0; }; [ \"$n\" = 1 ] || { echo AMBIGUOUS; exit 0; }; "
            + "echo DATA; base64 -w0 \"$f\"; echo; echo META; head -c 64 \"$f.crc\" | base64 -w0; echo; echo END";
    /** 点卡片跳进纪念日后，再看这几次（毫秒）：编辑完直接回负一屏、没息屏也能更新。 */
    private static final long[] FOLLOW_UP_MS = {30_000L, 90_000L, 300_000L};

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean reading;
    private static final java.util.concurrent.atomic.AtomicBoolean queued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static Runnable listener;
    private static boolean watching;
    private static final Runnable[] followUps = new Runnable[FOLLOW_UP_MS.length];

    private AnniversarySync() {
    }

    static boolean reading() {
        return reading;
    }

    /** 设置页在前台时接收“读取开始/结束”的通知；只保留一个。 */
    static void listen(Runnable callback) {
        listener = callback;
    }

    static void unlisten(Runnable callback) {
        if (listener == callback) listener = null;
    }

    private static void notifyListener() {
        MAIN.post(() -> {
            Runnable callback = listener;
            if (callback != null) callback.run();
        });
    }

    /** 后台读一次。不在纪念日模式时什么也不做，除非 force（设置页刚切到纪念日）。 */
    static void refresh(Context context, boolean force) {
        final Context app = context.getApplicationContext();
        if (!force && !CountdownStore.source(app).anniversary) return;
        // 几个触发点常常同时到（进程启动 + 设置页 onResume）：已经排着一次就不再排。
        if (!queued.compareAndSet(false, true)) return;
        IO.execute(() -> {
            queued.set(false);
            reading = true;
            notifyListener();
            try {
                boolean changed = read(app);
                if (changed) {
                    CountdownWidgetProvider.updateAll(app);
                    WidgetClock.schedule(app);
                }
            } finally {
                reading = false;
                notifyListener();
            }
        });
    }

    /** 上次读取不是今天就读一次（换日边界调用）。 */
    static void daily(Context context) {
        Context app = context.getApplicationContext();
        long readAt = AnniversaryStore.snapshot(app).readAt;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        if (readAt <= 0L || !java.time.Instant.ofEpochMilli(readAt).atZone(zone).toLocalDate()
                .equals(java.time.LocalDate.now(zone))) {
            refresh(app, false);
        }
    }

    /** 读纪念日并记进镜像；返回卡片要不要重画。 */
    private static boolean read(Context app) {
        String json;
        try {
            json = RootShell.session(app, 1800, 4000, adb -> {
                try {
                    return fetch(adb);
                } catch (AnniversaryData.TornReadException torn) {
                    // 纪念日正好在写：稍等再读一次，两次都对不上就当这次没读到。
                    Thread.sleep(300);
                    try {
                        return fetch(adb);
                    } catch (AnniversaryData.TornReadException again) {
                        throw new Unreadable(again.getMessage());
                    }
                }
            });
        } catch (Missing missing) {
            return AnniversaryStore.record(app, AnniversaryStore.MISSING, null, "");
        } catch (Unreadable unreadable) {
            Log.w(TAG, "Unable to parse anniversary data: " + unreadable.getMessage());
            return AnniversaryStore.record(app, AnniversaryStore.UNREADABLE, null, unreadable.getMessage());
        } catch (Exception failure) {
            // 连接、握手、命令超时都算授权通道问题。
            Log.w(TAG, "Unable to read anniversary data", failure);
            return AnniversaryStore.record(app, AnniversaryStore.NO_ACCESS, null, "本机授权通道不可用");
        }
        String value = json == null ? "[]" : json;
        try {
            // 先确认能解析再替换镜像：认不出来时保留上次读成功的内容。
            AnniversaryData.entries(value, java.time.ZoneId.systemDefault());
        } catch (IOException invalid) {
            return AnniversaryStore.record(app, AnniversaryStore.UNREADABLE, null, invalid.getMessage());
        }
        return AnniversaryStore.record(app, AnniversaryStore.OK, value, "");
    }

    /** 纪念日没装或从没存过数据。 */
    private static final class Missing extends Exception {
        Missing() {
            super("没有找到纪念日数据");
        }
    }

    /** 数据取回来了但认不出来；和连接失败分开。 */
    private static final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message == null ? "纪念日数据读不出来" : message);
        }
    }

    private static String fetch(AdbClient adb) throws Exception {
        String text = adb.shell(READ, 8000).trim();
        if (adb.exitCode() != 0) throw new IOException("读取命令没有完成");
        if (text.equals("MISSING")) throw new Missing();
        if (text.equals("AMBIGUOUS")) throw new Unreadable("纪念日数据文件不止一份，无法确定");
        String[] lines = text.split("\n");
        if (lines.length < 5 || !lines[0].trim().equals("DATA") || !lines[2].trim().equals("META")
                || !lines[4].trim().equals("END")) {
            throw new Unreadable("纪念日数据读取不完整");
        }
        byte[] data, meta;
        try {
            data = Base64.decode(lines[1].trim(), Base64.DEFAULT);
            meta = Base64.decode(lines[3].trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException invalid) {
            throw new Unreadable("纪念日数据读取不完整");
        }
        try {
            return AnniversaryData.storedJson(data, meta);
        } catch (AnniversaryData.TornReadException torn) {
            throw torn;
        } catch (IOException invalid) {
            throw new Unreadable(invalid.getMessage());
        }
    }

    /** 打开纪念日（卡片点击和设置页都走这里），并在之后几分钟里再看几次有没有改动。 */
    static void open(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("hap://app/" + APP))
                    .setPackage(ENGINE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException failure) {
            Toast.makeText(context, "无法打开纪念日", Toast.LENGTH_SHORT).show();
            return;
        }
        final Context app = context.getApplicationContext();
        for (int index = 0; index < FOLLOW_UP_MS.length; index++) {
            if (followUps[index] != null) MAIN.removeCallbacks(followUps[index]);
            followUps[index] = () -> checkSinceRead(app, true);
            MAIN.postDelayed(followUps[index], FOLLOW_UP_MS[index]);
        }
    }

    /** 进程启动时调用一次：登记亮屏检查；纪念日模式下顺带读一次。 */
    static synchronized void start(Context context) {
        Context app = context.getApplicationContext();
        if (!watching) {
            watching = true;
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    checkSinceRead(c.getApplicationContext(), false);
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON));
        }
        refresh(app, false);
    }

    /**
     * 上次读取之后快应用引擎到过前台，就再读一次。没有使用情况权限时：跟进检查照读，
     * 亮屏检查跳过（不然每次亮屏都要走一次 root）。
     */
    private static void checkSinceRead(Context app, boolean followUp) {
        if (reading || !CountdownStore.source(app).anniversary) return;
        if (!usageAllowed(app)) {
            if (followUp) refresh(app, false);
            return;
        }
        if (engineUsedSince(app, AnniversaryStore.snapshot(app).readAt)) refresh(app, false);
    }

    static boolean usageAllowed(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 引擎在 since 之后到过前台（含此刻仍在前台）。只看引擎包名，不知道也不需要知道是哪个快应用。 */
    private static boolean engineUsedSince(Context context, long since) {
        try {
            UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usage == null) return false;
            long now = System.currentTimeMillis();
            UsageEvents events = usage.queryEvents(Math.max(0L, since - 3_600_000L), now);
            UsageEvents.Event event = new UsageEvents.Event();
            boolean used = false, foreground = false;
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                if (!ENGINE.equals(event.getPackageName())) continue;
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foreground = true;
                    if (event.getTimeStamp() >= since) used = true;
                } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    if (event.getTimeStamp() >= since) used = true;
                    foreground = false;
                }
            }
            return used || foreground;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to query usage events", failure);
            return false;
        }
    }

    /** 切换模式时调用：纪念日模式授予本应用“使用情况访问”，切回自己设置时撤回。后台执行，失败只影响亮屏检查。 */
    static void usageAccess(Context context, boolean allow) {
        final Context app = context.getApplicationContext();
        if (usageAllowed(app) == allow) return;
        IO.execute(() -> {
            try {
                RootShell.session(app, 1800, 4000, adb -> adb.shell(
                        "appops set " + SELF + " GET_USAGE_STATS " + (allow ? "allow" : "default"), 6000));
            } catch (Exception failure) {
                Log.w(TAG, "Unable to change usage access", failure);
            }
        });
    }
}
