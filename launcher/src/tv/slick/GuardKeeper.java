package tv.slick;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Fire OS clears third-party accessibility services when Slick is updated, which turns
 * {@link HomeGuard} off. This turns it back on after an update, at boot, and whenever Home shows.
 * Needs WRITE_SECURE_SETTINGS, granted once over adb. On Fire OS 6, where HomeGuard can't run, it starts
 * {@link HomeWatcher} instead.
 */
public class GuardKeeper extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ensure(context);
    }

    static void ensure(Context context) {
        if (HomeWatcher.NEEDED) {
            HomeWatcher.start(context);
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("Grant it once: adb shell pm grant "
                    + context.getPackageName() + " " + Manifest.permission.WRITE_SECURE_SETTINGS);
        }
        String guard = new ComponentName(context, HomeGuard.class).flattenToString();
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled != null && (":" + enabled + ":").contains(":" + guard + ":")) return;
        String value = enabled == null || enabled.isEmpty() ? guard : enabled + ":" + guard;
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }
}
