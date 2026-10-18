package dev.codex.watchworkshop;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Protected Launcher entry. All root work runs in the private service. */
public final class CaptureEntryActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        String action=getIntent().getStringExtra("action");
        if("screenshot".equals(action)||"record".equals(action))CaptureService.request(this,action);
        else if("screenshot-settings".equals(action)||"record-settings".equals(action)){
            Class<?> target="screenshot-settings".equals(action)?CaptureScreenshotActivity.class:CaptureOptionsActivity.class;
            startActivity(new Intent(this,target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    |Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
        finishAndRemoveTask();
        overridePendingTransition(0,0);
    }
}
