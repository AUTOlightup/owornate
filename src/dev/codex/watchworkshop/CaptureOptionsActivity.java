package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

/**
 * 录屏设置页：选择码率后先退出本页，等页面真正离开前台再启动录屏。
 *
 * <p>码率保存为 bit/s，与原生 {@code screenrecord --bit-rate} 的单位一致。
 */
public final class CaptureOptionsActivity extends WatchActivity {

    private static final String PREFERENCES = "capture_options";
    private static final String KEY_BITRATE = "bitrate";
    private static final int LOW_BITRATE = 600000;
    private static final int DEFAULT_BITRATE = 1200000;
    private static final int HIGH_BITRATE = 2400000;
    private static final int START_DELAY_MS = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int bitrate = DEFAULT_BITRATE;
    private int screenWidth;
    private int screenHeight;
    private boolean pendingStart;
    private boolean reading, statusConfirmed;
    private String readError="";
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeChanged=(store,key)->{
        if(!destroyed())render();
    };

    @Override protected void onStart(){
        super.onStart();
        CaptureManager.preferences(this).registerOnSharedPreferenceChangeListener(runtimeChanged);
        refreshStatus();
    }

    private void refreshStatus(){
        if(reading)return;
        reading=true;statusConfirmed=false;readError="";render();
        CaptureService.refreshStatus(this,problem->{
            if(destroyed()||isFinishing())return;
            reading=false;statusConfirmed=problem==null;
            readError=problem==null?"":WatchActivity.message(problem);
            render();
        });
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        bitrate = loadBitrate();
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        if (state != null) remember("record", state.getInt("scroll", 0));
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("scroll", scrollY());
    }

    /** 开始按钮只允许进入一次离开流程；页面消失后才启动服务。 */
    private void startRecording() {
        if (pendingStart||reading||!statusConfirmed) return;
        pendingStart = true;
        saveBitrate();
        setResult(RESULT_OK);
        finishAndRemoveTask();
    }

    @Override protected void onStop() {
        CaptureManager.preferences(this).unregisterOnSharedPreferenceChangeListener(runtimeChanged);
        super.onStop();
        if (!pendingStart) return;
        pendingStart = false;
        final Context application = getApplicationContext();
        final int selectedBitrate = bitrate;
        handler.postDelayed(() -> CaptureService.startRecording(application, selectedBitrate),
                START_DELAY_MS);
    }

    /** 返回和取消都只关闭本页，让调用方原来的应用重新出现。 */
    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override public void onBackPressed() {
        cancel();
    }

    private void render() {
        rebuild("record", this::page);
    }

    private void page() {
        ui.header("录屏设置", false, this::cancel, null);
        ui.note("OWOrnate · 截屏与录屏模块");
        ui.text("选择画质后开始。设置页会先退出，再开始录屏，录下的第一帧不会停在本页。");

        ui.title("屏幕");
        ui.value("实际分辨率", screenWidth + " × " + screenHeight + " px");
        ui.value("帧率", "约 60 fps，跟随屏幕；实际随画面内容变化");
        ui.value("文件格式", "截屏 PNG / 录屏 H.264 MP4，无声");
        ui.value("单段上限", "3 分钟");
        ui.note("本页只设置录屏。");

        ui.title("画质");
        ui.choice("低 · 0.6 Mbps", "每分钟约 4.5 MB", bitrate == LOW_BITRATE,
                () -> choose(LOW_BITRATE));
        ui.choice("标准 · 1.2 Mbps", "每分钟约 9 MB", bitrate == DEFAULT_BITRATE,
                () -> choose(DEFAULT_BITRATE));
        ui.choice("高 · 2.4 Mbps", "每分钟约 18 MB", bitrate == HIGH_BITRATE,
                () -> choose(HIGH_BITRATE));
        ui.note("当前预计每分钟约 " + estimatedMegabytes() + " MB。选择后会保存。");

        String phase=CaptureManager.preferences(this).getString("phase", "idle");
        boolean active="starting".equals(phase)||"recording".equals(phase)||"stopping".equals(phase);
        if(reading)ui.note("正在确认当前录屏状态…");
        else if(!readError.isEmpty()){
            ui.note("状态读取失败："+readError,"warning");
            ui.button("重新读取状态",false,this::refreshStatus);
        }else if(active){
            ui.note("当前正在录制或保存，画质设置用于下一段录屏。");
            ui.button("重新读取状态",false,this::refreshStatus);
        }
        else {
            String error=CaptureManager.preferences(this).getString("error", "");
            if(!error.isEmpty())ui.note(error,"warning");
        }
        ui.button(statusConfirmed&&active?"返回原界面":"开始录屏", true,
                !pendingStart&&!reading&&statusConfirmed, active?this::cancel:this::startRecording);
        ui.button("取消", false, this::cancel);
        ui.note("取消或返回会回到原来的应用。开始后单段最多录制 3 分钟。");
    }

    private void choose(int value) {
        if (bitrate == value) return;
        bitrate = value;
        saveBitrate();
        render();
    }

    private int loadBitrate() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        int stored = preferences.getInt(KEY_BITRATE, DEFAULT_BITRATE);
        return stored == LOW_BITRATE || stored == DEFAULT_BITRATE || stored == HIGH_BITRATE
                ? stored : DEFAULT_BITRATE;
    }

    private void saveBitrate() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putInt(KEY_BITRATE, bitrate)
                .apply();
    }

    private String estimatedMegabytes() {
        if (bitrate == LOW_BITRATE) return "4.5";
        if (bitrate == HIGH_BITRATE) return "18";
        return "9";
    }
}
