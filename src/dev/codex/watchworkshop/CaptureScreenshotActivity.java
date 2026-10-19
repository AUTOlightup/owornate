package dev.codex.watchworkshop;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** OWOrnate screenshot page, also opened by the control tile's long press. */
public final class CaptureScreenshotActivity extends WatchActivity {
    private boolean pending;
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        rebuild("screenshot",()->{
            ui.header("截屏",false,this::onBackPressed,null);
            ui.note("OWOrnate · 截屏与录屏模块");
            ui.value("图片格式","原屏 PNG");
            ui.value("保存位置","Pictures/Screenshots");
            ui.note("短按控制中心截屏按钮，面板收起后截图；长按进入本页。");
            ui.button("返回后截屏",true,()->{
                if(pending)return;
                pending=true;
                if(isTaskRoot())finishAndRemoveTask();else finish();
            });
            ui.row("模块管理","安装、启用与停用","layers",()->startActivity(new Intent(this,CaptureSetupActivity.class)));
        });
    }
    @Override protected void onStop(){
        super.onStop();
        if(!pending)return;
        pending=false;
        final Context app=getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(()->CaptureService.request(app,"screenshot"),200);
    }
}
