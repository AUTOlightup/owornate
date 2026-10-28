package dev.codex.watchworkshop;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One finite boot/upgrade job; plugins own any process-restart recovery they declare. */
public final class PluginBootJob extends JobService {
    private static final int JOB_ID = 203901;
    private final ConcurrentHashMap<JobParameters, AtomicBoolean> running = new ConcurrentHashMap<>();

    static void schedule(Context context, int attempt) {
        PersistableBundle extras = new PersistableBundle();
        extras.putInt("attempt", attempt);
        long delay = attempt == 0 ? 3000 : attempt * 15000;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, PluginBootJob.class)).setExtras(extras)
                .setMinimumLatency(delay).setOverrideDeadline(delay + 15000).build());
    }

    static String failure(Context context, String id) {
        return context.getSharedPreferences("plugin_restore", 0).getString(id + ".error", "");
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        AtomicBoolean stopped = new AtomicBoolean();
        running.put(parameters, stopped);
        new Thread(() -> {
            boolean failed = false;
            try { synchronized (RuntimeSupport.LOCK) {
                PluginRepository repository = ExternalPlugins.repository(this);
                for (PluginManifest manifest : repository.list()) {
                    if (stopped.get()) break;
                    if (!manifest.restoreOnBoot || !repository.restoreRequested(manifest.id)) continue;
                    String error = "";
                    boolean enabled = false;
                    try {
                        enabled = RootShell.session(this, 1800, 15000, adb -> {
                            PluginRunner runner = new PluginRunner(repository, command -> {
                                String output = adb.shell(command, 20000);
                                return new PluginRunner.Result(adb.exitCode(), output);
                            });
                            return runner.restore(manifest.id);
                        });
                    } catch (Exception problem) {
                        error = problem.getMessage() == null ? problem.getClass().getSimpleName() : problem.getMessage();
                        if (error.length() > 300) error = error.substring(0, 300);
                        failed = true;
                    }
                    getSharedPreferences("plugin_restore", 0).edit()
                            .putLong(manifest.id + ".time", System.currentTimeMillis())
                            .putBoolean(manifest.id + ".enabled", enabled)
                            .putString(manifest.id + ".error", error).commit();
                }
            } } catch (Exception problem) {
                failed = true;
                android.util.Log.e("OWPluginRestore", "Cannot read startup state", problem);
            } finally {
                running.remove(parameters);
                if (!stopped.get()) {
                    jobFinished(parameters, false);
                    int attempt = parameters.getExtras().getInt("attempt", 0);
                    // adbd/user storage may still be coming up; do not retry indefinitely.
                    if (failed && attempt < 2) schedule(this, attempt + 1);
                }
            }
        }, "ow-plugin-restore").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        AtomicBoolean stopped = running.get(parameters);
        if (stopped != null) stopped.set(true);
        // Let an in-flight root command finish under the existing lock.
        return false;
    }
}
