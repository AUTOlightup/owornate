package dev.codex.watchworkshop;

import android.app.Activity;
import android.os.Bundle;

/**
 * 纪念日模式下点倒计时卡片：经这里打开纪念日，并让 {@link AnniversarySync} 在之后几分钟里
 * 再看几次有没有改动。不显示界面，打开后立刻结束；不导出。
 */
public final class AnniversaryLaunchActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AnniversarySync.open(this);
        finish();
    }
}
