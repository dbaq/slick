package tv.slick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Receives this TV's Home Assistant setup from a computer (launcher/home-setup.sh). Only the adb shell can
 * send it: the manifest requires WRITE_SECURE_SETTINGS of the sender, which ordinary apps can't hold.
 * Extras: url, token, devices as "entity_id=Name;entity_id=Name" (Name may be empty), and optionally
 * doorbell as "binary_sensor.ring;camera.one;camera.two".
 */
public class HomeSetup extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String url = intent.getStringExtra("url");
        String token = intent.getStringExtra("token");
        String list = intent.getStringExtra("devices");
        if (url == null || token == null || list == null) throw new IllegalArgumentException("url, token and devices are required");
        List<Home.Device> devices = new ArrayList<>();
        for (String entry : list.split(";")) {
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            devices.add(eq < 0 ? new Home.Device(entry, "") : new Home.Device(entry.substring(0, eq), entry.substring(eq + 1)));
        }
        String bell = intent.getStringExtra("doorbell");
        List<String> doorbell = new ArrayList<>();
        for (String id : (bell == null ? "" : bell).split(";")) if (!id.isEmpty()) doorbell.add(id);
        new Home(url, token, devices, doorbell).save(context);
        Log.i("Slick", "Home Assistant set up with " + devices.size() + " devices");
    }
}
