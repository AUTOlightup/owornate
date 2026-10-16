package dev.codex.watchworkshop;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** API 27 RemoteViews widget. Rendering reads local cache only; no polling or root calls. */
public final class OWOrnateWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){
        for(int id:ids)update(context,manager,id);
    }
    @Override public void onRestored(Context context,int[] oldIds,int[] newIds){updateAll(context);}
    static void updateAll(Context context){
        try{
            AppWidgetManager manager=AppWidgetManager.getInstance(context);
            for(int id:manager.getAppWidgetIds(new ComponentName(context,OWOrnateWidgetProvider.class)))update(context,manager,id);
        }catch(RuntimeException e){Log.w("OWOrnateWidget","Unable to notify widget host",e);}
    }
    private static void update(Context context,AppWidgetManager manager,int id){manager.updateAppWidget(id,render(context,id));}
    /** id 只决定点按打开哪张卡片的设置；显示内容所有卡片相同。id 为 -1 时用于设置页预览。 */
    static RemoteViews render(Context context,int id){
        OWOrnateWidgetStore.Options options=OWOrnateWidgetStore.options(context);
        OWOrnateWidgetSnapshot state=OWOrnateWidgetStore.snapshot(context);
        RemoteViews view=new RemoteViews(context.getPackageName(),R.layout.owornate_widget);
        String version="";
        try{version="v"+context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName.replace("-widget-dev"," dev");}catch(Exception ignored){}
        view.setTextViewText(R.id.widget_version,version);
        view.setTextViewText(R.id.widget_status,state.statusText());
        view.setTextViewText(R.id.widget_theme,state.themeText());
        view.setViewVisibility(R.id.widget_version,options.showVersion?View.VISIBLE:View.GONE);
        view.setViewVisibility(R.id.widget_status,options.showStatus?View.VISIBLE:View.GONE);
        view.setViewVisibility(R.id.widget_theme,options.showTheme?View.VISIBLE:View.GONE);
        String when=state.confirmedAt==0?"尚未确认":new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(state.confirmedAt))+" 确认";
        view.setTextViewText(R.id.widget_updated,(options.showStatus||options.showTheme?when+" · ":"")+"点按编辑");
        Intent edit=new Intent(context,OWOrnateWidgetSettingsActivity.class)
            .setData(Uri.parse("owornate://widget/"+id))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);
        PendingIntent click=PendingIntent.getActivity(context,id,edit,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root,click);
        view.setContentDescription(R.id.widget_root,"OWOrnate，"+(options.showStatus?state.statusText()+"，":"")+(options.showTheme?state.themeText()+"，":"")+"点按编辑组件");
        return view;
    }
}
