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

/** OWOrnate 信息小组件（API 27 RemoteViews）。渲染只读本地缓存，不轮询，也不走 root。 */
public final class OWOrnateWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        updateAll(context);
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            for (int id : manager.getAppWidgetIds(new ComponentName(context, OWOrnateWidgetProvider.class))) {
                update(context, manager, id);
            }
        } catch (RuntimeException e) {
            Log.w("OWOrnateWidget", "Unable to notify widget host", e);
        }
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        manager.updateAppWidget(id, render(context, id));
    }

    /** id 只决定点按打开哪张卡片的设置；显示内容所有卡片相同。id 为 -1 时用于设置页预览。 */
    static RemoteViews render(Context context, int id) {
        OWOrnateWidgetStore.Options options = OWOrnateWidgetStore.options(context);
        OWOrnateWidgetSnapshot state = OWOrnateWidgetStore.snapshot(context);
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.owornate_widget);
        view.setTextViewText(R.id.widget_version, versionText(context));
        view.setTextViewText(R.id.widget_status, state.statusText());
        view.setTextViewText(R.id.widget_theme, state.themeText());
        view.setViewVisibility(R.id.widget_version, options.showVersion ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.widget_status, options.showStatus ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.widget_theme, options.showTheme ? View.VISIBLE : View.GONE);
        boolean showsState = options.showStatus || options.showTheme;
        view.setTextViewText(R.id.widget_updated, (showsState ? confirmedText(state) + " · " : "") + "点按编辑");
        view.setOnClickPendingIntent(R.id.widget_root, settingsIntent(context, id));
        view.setContentDescription(R.id.widget_root, "OWOrnate，"
                + (options.showStatus ? state.statusText() + "，" : "")
                + (options.showTheme ? state.themeText() + "，" : "")
                + "点按编辑组件");
        return view;
    }

    /** “v1.0.1-rc10”；读不到版本时留空。 */
    private static String versionText(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return "v" + name.replace("-widget-dev", " dev");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String confirmedText(OWOrnateWidgetSnapshot state) {
        if (state.confirmedAt == 0) return "尚未确认";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(state.confirmedAt)) + " 确认";
    }

    private static PendingIntent settingsIntent(Context context, int id) {
        Intent edit = new Intent(context, OWOrnateWidgetSettingsActivity.class)
                .setData(Uri.parse("owornate://widget/" + id))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getActivity(context, id, edit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
