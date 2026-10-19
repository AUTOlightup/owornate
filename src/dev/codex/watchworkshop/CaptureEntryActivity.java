package dev.codex.watchworkshop;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/** Protected Launcher entry. All root work runs in the private service. */
public final class CaptureEntryActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        String action=getIntent().getStringExtra("action");
        // The system setting only selects the entry route. The page/service confirms
        // the real native session before allowing a start or stopping a recording.
        if("record".equals(action)){
            String phase=Settings.System.getString(getContentResolver(),"codex_capture_phase");
            if("starting".equals(phase)||"recording".equals(phase)||"stopping".equals(phase))
                CaptureService.request(this,action);
            else open(CaptureOptionsActivity.class);
        }
        else if("screenshot".equals(action))CaptureService.request(this,action);
        else if("screenshot-settings".equals(action)||"record-settings".equals(action)){
            Class<?> target="screenshot-settings".equals(action)?CaptureScreenshotActivity.class:CaptureOptionsActivity.class;
            open(target);
        }
        finishAndRemoveTask();
        overridePendingTransition(0,0);
    }
    private void open(Class<?> target){
        startActivity(new Intent(this,target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                |Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
}
