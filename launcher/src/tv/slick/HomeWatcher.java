package tv.slick;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

/**
 * Fire OS 6 (Android 7.1) won't start third-party accessibility services, so {@link HomeGuard} never runs
 * there. On those TVs this watches which screen comes to the front instead, and sends the Amazon home
 * screen back to Slick. It only sees screens, not remote presses: no ☰ drawer or doorbell card there.
 * Needs usage access, granted once over adb.
 */
public class HomeWatcher extends Service {

    /** The TVs HomeGuard can't run on. */
    static final boolean NEEDED = Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1;

    private static final long POLL_MS = 500;
    /** Events can land a little after their time stamp; each look overlaps the last by this much. */
    private static final long OVERLAP_MS = 10_000;
    /** The first look reaches back this far, for the Amazon home screen already up when Slick starts. */
    private static final long FIRST_LOOK_MS = 24 * 60 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private UsageStatsManager usage;
    private PowerManager power;
    private long lookFrom;
    /** Time stamp of the last Amazon home screen already sent back, so it's sent back once. */
    private long handled;

    static void start(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                != AppOpsManager.MODE_ALLOWED) {
            throw new IllegalStateException("Grant it once: adb shell appops set "
                    + context.getPackageName() + " GET_USAGE_STATS allow");
        }
        context.startService(new Intent(context, HomeWatcher.class));
    }

    @Override
    public void onCreate() {
        usage = getSystemService(UsageStatsManager.class);
        power = getSystemService(PowerManager.class);
        lookFrom = System.currentTimeMillis() - FIRST_LOOK_MS;
        // In the foreground so a big app's memory needs don't stop it.
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Slick")
                .setPriority(Notification.PRIORITY_MIN)
                .build());
        main.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void tick() {
        if (power.isInteractive()) check();
        main.postDelayed(tick, POLL_MS);
    }

    private void check() {
        long now = System.currentTimeMillis();
        UsageEvents events = usage.queryEvents(lookFrom, now);
        lookFrom = now - OVERLAP_MS;
        UsageEvents.Event e = new UsageEvents.Event();
        String front = null;
        long at = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
            front = e.getClassName();
            at = e.getTimeStamp();
        }
        if (!HomeGuard.AMAZON_HOME.equals(front) || at <= handled) return;
        handled = at;
        HomeGuard.goHome(this);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
