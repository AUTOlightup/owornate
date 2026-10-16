package dev.codex.watchworkshop;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import java.time.*;

public final class TimetableWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){updateAll(c);WidgetClock.schedule(c);}
    @Override public void onDeleted(Context c,int[] ids){WidgetClock.schedule(c);}
    @Override public void onDisabled(Context c){WidgetClock.schedule(c);}
    static int[] ids(Context c){return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c,TimetableWidgetProvider.class));}
    static void updateAll(Context c){for(int id:ids(c))AppWidgetManager.getInstance(c).updateAppWidget(id,render(c,id));}
    /** 下一次要重画的时刻：焦点课程变化的边界；上课中再加上下一个整分钟（“还有 N 分钟”）。所有卡片共用一份课表，只算一次。 */
    static long nextUpdate(Context c,long now){
        if(ids(c).length==0)return Long.MAX_VALUE;
        try{
            TimetableData d=TimetableStore.read(c);
            if(d==null)return Long.MAX_VALUE;
            TimetableFocus.State s=TimetableFocus.at(d,Instant.ofEpochMilli(now).atZone(TimetableStore.zone(c)));
            return s.ongoing?Math.min(s.nextChange,(now/60000+1)*60000):s.nextChange;
        }catch(Exception ignored){return Long.MAX_VALUE;}
    }
    /** id 只决定点按打开哪张卡片；显示内容所有卡片相同。id 为 -1 时用于设置页预览。 */
    static RemoteViews render(Context c,int id){
        RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.timetable_widget);
        try{
            TimetableData data=TimetableStore.read(c);
            TimetableFocus.State s=TimetableFocus.at(data,ZonedDateTime.now(TimetableStore.zone(c)));
            if(s.focus==null){empty(v,s.label,s.footer);}
            else{
                v.setViewVisibility(R.id.timetable_empty,View.GONE);v.setViewVisibility(R.id.timetable_content,View.VISIBLE);
                v.setInt(R.id.timetable_root,"setBackgroundResource",s.today?(s.ongoing?R.drawable.timetable_bg_current:R.drawable.timetable_bg_today):R.drawable.timetable_bg_future);
                v.setTextViewText(R.id.timetable_label,s.label);
                int ink=s.today?(s.ongoing?0xff06222f:0xff8ad8fb):0xffcbc2fa;
                v.setTextColor(R.id.timetable_label,ink);
                v.setInt(R.id.timetable_label,"setBackgroundResource",s.today?(s.ongoing?R.drawable.timetable_pill_current:R.drawable.timetable_pill_today):R.drawable.timetable_pill_future);
                String time=fitTime(c,s);
                v.setTextViewText(R.id.timetable_time,time);
                String name=s.focus.course.name;boolean longName=width(c,name,20)>157;
                v.setViewVisibility(R.id.timetable_name, longName?View.GONE:View.VISIBLE);
                v.setViewVisibility(R.id.timetable_name_long,longName?View.VISIBLE:View.GONE);
                v.setTextViewText(longName?R.id.timetable_name_long:R.id.timetable_name,name);
                v.setViewVisibility(R.id.timetable_details,longName?View.GONE:View.VISIBLE);
                v.setViewVisibility(R.id.timetable_details_long,longName?View.VISIBLE:View.GONE);
                v.setTextViewText(longName?R.id.timetable_details_long:R.id.timetable_details,details(c,s.focus.course));
                v.setViewVisibility(R.id.timetable_line,longName?View.GONE:View.VISIBLE);
                v.setViewVisibility(R.id.timetable_footer,longName?View.GONE:View.VISIBLE);
                v.setTextViewText(R.id.timetable_footer,s.footer);
            }
        }catch(Exception error){empty(v,"暂时无法读取课表","点按重新导入");}
        Intent edit=new Intent(c,TimetableSettingsActivity.class).setData(Uri.parse("owornate://timetable/"+id)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);
        v.setOnClickPendingIntent(R.id.timetable_root,PendingIntent.getActivity(c,id,edit,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
    private static void empty(RemoteViews v,String title,String hint){
        v.setViewVisibility(R.id.timetable_content,View.GONE);v.setViewVisibility(R.id.timetable_empty,View.VISIBLE);
        v.setTextViewText(R.id.timetable_empty_title,title);v.setTextViewText(R.id.timetable_empty_hint,hint);
    }
    private static float width(Context c,String text,int sp){Paint p=new Paint();p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(sp*c.getResources().getDisplayMetrics().scaledDensity);return p.measureText(text)/c.getResources().getDisplayMetrics().density;}
    /** 右上角文字：上课中显示“下课时刻 · 还有 N 分钟”，宽度不够时依次去掉“下课”、再去掉时刻；其余状态沿用原文案。 */
    private static String fitTime(Context c,TimetableFocus.State s){
        if(!s.ongoing||s.focus==null){
            String time=s.time;
            return width(c,s.label,11)+width(c,time,12)+18>157?time.substring(0,5):time;
        }
        long minutes=Math.max(1,(s.focus.end.toInstant().toEpochMilli()-System.currentTimeMillis()+59999)/60000);
        String left=minutes>=60?"还有 "+minutes/60+" 小时"+(minutes%60==0?"":" "+minutes%60+" 分钟"):"还有 "+minutes+" 分钟";
        String end=s.time.substring(0,5);
        String[] options={end+" 下课 · "+left,end+" · "+left,left};
        for(String option:options)if(width(c,s.label,11)+width(c,option,12)+18<=157)return option;
        return options[options.length-1];
    }
    /** 课程名下面的小字只留教师名称；没有教师时这一行留空。 */
    private static String details(Context c,TimetableData.Course course){
        return course.teacher;
    }
}