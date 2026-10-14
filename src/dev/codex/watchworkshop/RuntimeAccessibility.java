package dev.codex.watchworkshop;
import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/** System startup trigger. Does not inspect nodes, text or gestures. */
public final class RuntimeAccessibility extends AccessibilityService {
    private long last=-60000;
    protected void onServiceConnected(){last=SystemClock.elapsedRealtime();RuntimeSupport.ensureAsync(this,true);}
    public void onAccessibilityEvent(AccessibilityEvent event){if(event!=null&&"com.heytap.wearable.launcher".contentEquals(event.getPackageName()==null?"":event.getPackageName()))arm();}
    public void onInterrupt(){}
    private void arm(){long now=SystemClock.elapsedRealtime();if(now-last<60000)return;last=now;RuntimeSupport.ensureAsync(this,false);}
}
