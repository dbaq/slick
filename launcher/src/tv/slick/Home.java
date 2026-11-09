package tv.slick;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * This TV's Home Assistant: where it is, the token, and the devices on its panel, in order. Set from a
 * computer over adb ({@link HomeSetup}); the device list is then edited in Settings. Only lights, climate,
 * scripts and speaker volume can ever be controlled: every other kind of entity (locks, alarms, garage
 * doors, covers, sirens) is refused here, whatever the list says. Network calls block: call them off the
 * main thread.
 */
final class Home {

    /** What a device is on the panel. Nothing outside these four domains is ever touched. */
    enum Kind {
        SOUND("media_player"), CLIMATE("climate"), LIGHT("light"), SCRIPT("script");

        final String domain;

        Kind(String domain) {
            this.domain = domain;
        }

        /** The kind for an entity id, or null when Slick must never control it. */
        static Kind of(String entityId) {
            String domain = entityId.substring(0, entityId.indexOf('.'));
            for (Kind k : values()) if (k.domain.equals(domain)) return k;
            return null;
        }
    }

    /** A device on the panel; {@code name} is "" to use Home Assistant's own name. */
    static final class Device {
        final String entityId;
        final String name;
        final Kind kind;

        Device(String entityId, String name) {
            this.entityId = entityId;
            this.name = name;
            kind = Kind.of(entityId);
            if (kind == null) throw new IllegalArgumentException("Slick doesn't control " + entityId);
        }
    }

    static final int MAX_DEVICES = 12;
    private static final String FILE = "home.json";

    final String url;
    private final String token;
    final List<Device> devices;
    /**
     * The doorbell: its ring sensor (a binary_sensor that turns on at each ring) followed by the cameras
     * to show when it rings, each as "small" or "small|large" (a low-resolution stream for the corner card
     * and a sharp one for the enlarged view). Empty when the house has none. Only ever read, never controlled.
     */
    final List<String> doorbell;

    Home(String url, String token, List<Device> devices, List<String> doorbell) {
        if (devices.size() > MAX_DEVICES) throw new IllegalArgumentException("At most " + MAX_DEVICES + " devices");
        if (!doorbell.isEmpty() && (!doorbell.get(0).startsWith("binary_sensor.") || doorbell.size() < 2)) {
            throw new IllegalArgumentException("doorbell is a binary_sensor followed by cameras: " + doorbell);
        }
        for (String cameras : doorbell.subList(Math.min(1, doorbell.size()), doorbell.size())) {
            for (String camera : cameras.split("\\|")) {
                if (!camera.startsWith("camera.")) throw new IllegalArgumentException("not a camera: " + camera);
            }
        }
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
        this.devices = devices;
        this.doorbell = doorbell;
    }

    /** This TV's setup, or null when Home Assistant hasn't been set up on it. */
    static Home load(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(TextFile.read(f));
            JSONArray a = o.getJSONArray("devices");
            List<Device> devices = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                devices.add(new Device(a.getJSONObject(i).getString("id"), a.getJSONObject(i).getString("name")));
            }
            List<String> doorbell = new ArrayList<>();
            JSONArray d = o.optJSONArray("doorbell");
            for (int i = 0; d != null && i < d.length(); i++) doorbell.add(d.getString(i));
            return new Home(o.getString("url"), o.getString("token"), devices, doorbell);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("home.json unreadable", e);
        }
    }

    Home withDevices(List<Device> devices) {
        return new Home(url, token, devices, doorbell);
    }

    void save(Context context) {
        try {
            JSONArray a = new JSONArray();
            for (Device d : devices) a.put(new JSONObject().put("id", d.entityId).put("name", d.name));
            JSONObject o = new JSONObject().put("url", url).put("token", token).put("devices", a)
                    .put("doorbell", new JSONArray(doorbell));
            File f = new File(context.getFilesDir(), FILE);
            File tmp = new File(f.getPath() + ".part");
            TextFile.write(tmp, o.toString());
            if (!tmp.renameTo(f)) throw new IOException("rename " + f);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("home.json not saved", e);
        }
    }

    // ---- Home Assistant's REST API ----------------------------------------------------------------

    JSONObject state(String entityId) throws IOException, JSONException {
        return new JSONObject(request("GET", "/api/states/" + entityId, null));
    }

    /** A camera's current picture, as JPEG bytes. */
    byte[] snapshot(String cameraId) throws IOException {
        if (!cameraId.startsWith("camera.")) throw new IllegalArgumentException(cameraId);
        return send("GET", "/api/camera_proxy/" + cameraId, null);
    }

    /** Every entity Slick could put on the panel. */
    List<JSONObject> controllable() throws IOException, JSONException {
        JSONArray all = new JSONArray(request("GET", "/api/states", null));
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.getJSONObject(i);
            if (Kind.of(s.getString("entity_id")) != null) out.add(s);
        }
        return out;
    }

    /** Calls a service on one device; refuses anything outside the device's own domain. */
    void call(Device device, String service, JSONObject data) throws IOException, JSONException {
        data.put("entity_id", device.entityId);
        request("POST", "/api/services/" + device.kind.domain + "/" + service, data.toString());
    }

    private String request(String method, String path, String body) throws IOException {
        return new String(send(method, path, body), "UTF-8");
    }

    private byte[] send(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
            }
            if (c.getResponseCode() / 100 != 2) throw new IOException(method + " " + path + " -> HTTP " + c.getResponseCode());
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(HttpURLConnection c) throws IOException {
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
