package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.IOException;

/** 信息卡片的显示设置（全局一份）和最近一次确认过的莫奈运行状态。 */
final class OWOrnateWidgetStore {
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("owornate_widget",0);}
    static final class Options {
        public final boolean showStatus,showTheme,showVersion;
        Options(boolean status,boolean theme,boolean version){showStatus=status;showTheme=theme;showVersion=version;}
    }
    /** 无论负一屏上有几张信息卡片，都读同一份显示设置。 */
    private static final String SLOT="recent.";
    private static boolean migrated;
    /** code11 及更早按卡片分别存；全局槽还没有数据时挑一份搬过来，每个进程只检查一次。 */
    private static synchronized void migrate(Context context){
        if(migrated)return;
        migrated=true;
        SharedPreferences p=prefs(context);
        if(p.contains(SLOT+"status"))return;
        String source=p.contains("default.status")?"default.":null;
        if(source==null)for(java.util.Map.Entry<String,?> entry:p.getAll().entrySet()){
            String key=entry.getKey();
            if(key.startsWith("widget.")&&key.endsWith(".status")){source=key.substring(0,key.length()-"status".length());break;}
        }
        if(source==null)return;
        p.edit().putBoolean(SLOT+"status",p.getBoolean(source+"status",true)).putBoolean(SLOT+"theme",p.getBoolean(source+"theme",true)).putBoolean(SLOT+"version",p.getBoolean(source+"version",true)).commit();
    }
    static Options options(Context context){
        migrate(context);
        SharedPreferences p=prefs(context);
        return new Options(p.getBoolean(SLOT+"status",true),p.getBoolean(SLOT+"theme",true),p.getBoolean(SLOT+"version",true));
    }
    static synchronized void saveOptions(Context context,Options options)throws IOException {
        migrate(context);
        if(!prefs(context).edit().putBoolean(SLOT+"status",options.showStatus).putBoolean(SLOT+"theme",options.showTheme).putBoolean(SLOT+"version",options.showVersion).commit())throw new IOException("组件设置未保存，请重试");
    }
    static synchronized OWOrnateWidgetSnapshot snapshot(Context context){
        SharedPreferences p=prefs(context);ThemeConfig theme=null;
        try{if(p.contains("mode"))theme=new ThemeConfig(p.getString("mode",""),p.getInt("seed",WorkshopUi.ACCENT),p.getBoolean("corners",false));}catch(IllegalArgumentException ignored){}
        return new OWOrnateWidgetSnapshot(p.getString("kind","unknown"),p.getBoolean("known",false),p.getBoolean("active",false),theme,p.getLong("confirmedAt",0));
    }
    static synchronized void recordModule(Context context,ModuleManager.State state){
        if(state==null){invalidate(context);return;}
        // A connection/setup failure is not evidence that a previously installed module vanished.
        boolean known=state.activeKnown&&!"setup".equals(state.kind)&&!"conflict".equals(state.kind);
        write(context,new OWOrnateWidgetSnapshot(state.kind,known,state.active,state.theme,System.currentTimeMillis()));
    }
    static synchronized void recordAppliedTheme(Context context,ThemeConfig theme){
        OWOrnateWidgetSnapshot old=snapshot(context);
        // Called only after a matching apply/rollback ACK and persisted enabled intent.
        if(old.installed())write(context,new OWOrnateWidgetSnapshot(old.kind,true,true,theme,System.currentTimeMillis()));
    }
    static synchronized void invalidate(Context context){
        OWOrnateWidgetSnapshot old=snapshot(context);
        write(context,new OWOrnateWidgetSnapshot(old.kind,false,false,null,0));
    }
    private static void write(Context context,OWOrnateWidgetSnapshot value){
        SharedPreferences.Editor e=prefs(context).edit().putString("kind",value.kind).putBoolean("known",value.activeKnown).putBoolean("active",value.active).putLong("confirmedAt",value.confirmedAt);
        if(value.theme==null)e.remove("mode").remove("seed").remove("corners");
        else e.putString("mode",value.theme.mode).putInt("seed",value.theme.seed).putBoolean("corners",value.theme.corners);
        // Widget bookkeeping must not convert a successful module operation into a failure.
        if(!e.commit())Log.w("OWOrnateWidget","Unable to persist widget observation");
        OWOrnateWidgetProvider.updateAll(context);
    }
}
