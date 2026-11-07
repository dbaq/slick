package tv.slick;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home Assistant controls in a glass panel on the right of the home screen: sound and climate as big
 * tiles, then lights and shortcuts as small ones. OK toggles a light (hold OK to dim it) or runs a
 * shortcut; on sound and climate, OK starts adjusting: Left/Right change the volume or the target
 * temperature, Up/Down the climate mode, and OK or Back stop.
 */
final class HomePanel extends FrameLayout {

    private static final long REFRESH_MS = 5000;
    private static final double VOLUME_STEP = 0.02;
    private static final int DIM_STEP = 10;

    private static final int OFF_BG = 0x1AFFFFFF;
    private static final int ON_BG = 0x40FFFFFF;
    private static final int WARM_LIGHT = 0xFFFCD34D;
    private static final int HEAT = 0xFFFB923C;
    private static final int COOL = 0xFF60A5FA;
    private static final int OTHER_MODE = 0xFF5EEAD4;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService network = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private final LinearLayout content;
    private final TextView status;
    private final List<Tile> tiles = new ArrayList<>();
    private final Runnable refresh = this::refresh;
    private Home home;
    private Tile adjusting;
    /** The OK release that ends a long press must not end the dimming it just started. */
    private boolean swallowNextOkUp;
    private boolean open;

    HomePanel(Activity activity, Home home) {
        super(activity);
        this.activity = activity;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xFF121418);
        setBackground(bg);
        setVisibility(GONE);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(14));
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        status = text("", 12, 0xFFFBBF24);
        status.setPadding(dp(16), dp(12), dp(16), 0);
        status.setVisibility(GONE);
        column.addView(status);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        addView(column, new LayoutParams(-1, -2));
        setHome(home);
    }

    boolean isOpen() {
        return open;
    }

    /** Rebuilds the tiles when Settings changed the device list. */
    void setHome(Home fresh) {
        boolean sameTiles = home != null && sameDevices(home, fresh);
        // Always take the fresh setup: a new token or address arrives with the same devices.
        home = fresh;
        if (sameTiles) return;
        adjusting = null;
        tiles.clear();
        content.removeAllViews();
        List<Tile> big = new ArrayList<>();
        List<Tile> lights = new ArrayList<>();
        List<Tile> shortcuts = new ArrayList<>();
        for (Home.Device d : home.devices) {
            Tile t = new Tile(d);
            tiles.add(t);
            (d.kind == Home.Kind.LIGHT ? lights : d.kind == Home.Kind.SCRIPT ? shortcuts : big).add(t);
        }
        grid(big, 2, 88);
        section("Lights", lights);
        section("Shortcuts", shortcuts);
        if (open) refresh();
    }

    void open() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        setTranslationX(dp(440));
        animate().translationX(0).setInterpolator(ease).setDuration(260).start();
        if (!tiles.isEmpty()) tiles.get(0).view.requestFocus();
        refresh();
    }

    void close() {
        if (!open) return;
        open = false;
        adjusting = null;
        main.removeCallbacks(refresh);
        animate().translationX(dp(440)).setInterpolator(ease).setDuration(200)
                .withEndAction(() -> {
                    if (!open) setVisibility(GONE);
                }).start();
    }

    /** The first climate device's room temperature ("72°"), or nothing when there's none. */
    void roomTemperature(Consumer<String> out) {
        for (Home.Device d : home.devices) {
            if (d.kind != Home.Kind.CLIMATE) continue;
            Home h = home;
            network.execute(() -> {
                try {
                    JSONObject a = h.state(d.entityId).getJSONObject("attributes");
                    if (a.isNull("current_temperature")) return;
                    String t = Math.round(a.getDouble("current_temperature")) + "°";
                    main.post(() -> out.accept(t));
                } catch (IOException | JSONException e) {
                    Log.w("Slick", "room temperature unavailable", e);
                }
            });
            return;
        }
    }

    /** Arrows never leave the panel while it's open. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!open) return false;
        if (super.dispatchKeyEvent(event)) return true;
        int direction = directionOf(event.getKeyCode());
        if (direction == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View next = FocusFinder.getInstance().findNextFocus(this, findFocus(), direction);
            if (next != null) next.requestFocus();
        }
        return true;
    }

    private static int directionOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }

    // ---- State -------------------------------------------------------------------------------------

    private void refresh() {
        main.removeCallbacks(refresh);
        if (!open) return;
        Home h = home;
        List<Tile> now = new ArrayList<>(tiles);
        network.execute(() -> {
            try {
                for (Tile t : now) {
                    JSONObject state = h.state(t.device.entityId);
                    main.post(() -> t.apply(state));
                }
                main.post(() -> status.setVisibility(GONE));
            } catch (IOException | JSONException e) {
                Log.w("Slick", "Home Assistant refresh failed", e);
                main.post(() -> showError());
            }
        });
        main.postDelayed(refresh, REFRESH_MS);
    }

    private void call(Tile t, String service, JSONObject data) {
        Home h = home;
        network.execute(() -> {
            try {
                h.call(t.device, service, data);
            } catch (IOException | JSONException e) {
                Log.w("Slick", "Home Assistant call failed", e);
                main.post(this::showError);
            }
        });
    }

    private void showError() {
        status.setText("Can't reach Home Assistant");
        status.setVisibility(VISIBLE);
    }

    // ---- Layout ------------------------------------------------------------------------------------

    private void section(String title, List<Tile> group) {
        if (group.isEmpty()) return;
        TextView label = text(title, 12, 0x8CFFFFFF);
        label.setPadding(dp(2), content.getChildCount() == 0 ? 0 : dp(10), 0, dp(6));
        content.addView(label);
        grid(group, 3, 68);
    }

    private void grid(List<Tile> group, int columns, int heightDp) {
        for (int i = 0; i < group.size(); i += columns) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // A lone big tile takes the whole row; small tiles keep their size and leave a gap.
            boolean lone = columns == 2 && i + 1 == group.size();
            for (int c = 0; c < (lone ? 1 : columns); c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
                if (c > 0) lp.leftMargin = dp(8);
                row.addView(i + c < group.size() ? group.get(i + c).view : new Space(activity), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (content.getChildCount() > 0 && !(content.getChildAt(content.getChildCount() - 1) instanceof TextView)) {
                rp.topMargin = dp(8);
            }
            content.addView(row, rp);
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = SettingsActivity.Ui.text(activity, s, sp, SettingsActivity.Ui.REGULAR, color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(int v) {
        return SettingsActivity.Ui.dp(activity, v);
    }

    private static boolean sameDevices(Home a, Home b) {
        if (a.devices.size() != b.devices.size()) return false;
        for (int i = 0; i < a.devices.size(); i++) {
            if (!a.devices.get(i).entityId.equals(b.devices.get(i).entityId)
                    || !a.devices.get(i).name.equals(b.devices.get(i).name)) return false;
        }
        return true;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- One device --------------------------------------------------------------------------------

    private final class Tile {
        final Home.Device device;
        final LinearLayout view;
        private final GlyphDrawable glyph;
        private final TextView name;
        private final TextView value;
        private final TextView detail;
        private final GradientDrawable bg = new GradientDrawable();
        private final boolean big;

        private boolean known;
        private boolean available = true;
        private String state = "";
        private String friendlyName = "";
        private int brightness;
        private boolean dimmable;
        private double volume;
        private int target;
        private int current;
        private int minTemp = 50;
        private int maxTemp = 90;
        private final List<String> modes = new ArrayList<>();
        private boolean flashDone;

        Tile(Home.Device device) {
            this.device = device;
            big = device.kind == Home.Kind.SOUND || device.kind == Home.Kind.CLIMATE;
            view = new LinearLayout(activity);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setFocusable(true);
            view.setPadding(dp(12), dp(10), dp(12), dp(10));
            bg.setCornerRadius(dp(12));
            view.setBackground(bg);

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            glyph = new GlyphDrawable(glyphOf(device.kind));
            View icon = new View(activity);
            icon.setBackground(glyph);
            top.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            value = text("", big ? 20 : 12, Color.WHITE);
            value.setGravity(Gravity.END);
            top.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
            view.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));

            name = text("", 13, Color.WHITE);
            view.addView(name);
            detail = text("", 11, 0x99FFFFFF);
            if (big) view.addView(detail);

            view.setOnFocusChangeListener((v, focused) -> {
                if (!focused && adjusting == this) adjusting = null;
                render();
            });
            view.setOnClickListener(v -> press());
            view.setOnLongClickListener(v -> {
                if (device.kind != Home.Kind.LIGHT || !dimmable) return false;
                adjusting = this;
                swallowNextOkUp = true;
                render();
                return true;
            });
            view.setOnKeyListener((v, keyCode, event) -> adjusting == this && adjustKey(keyCode, event));
            render();
        }

        void apply(JSONObject s) {
            try {
                known = true;
                state = s.getString("state");
                available = !state.equals("unavailable") && !state.equals("unknown");
                JSONObject a = s.getJSONObject("attributes");
                friendlyName = a.optString("friendly_name");
                switch (device.kind) {
                    case LIGHT:
                        brightness = a.isNull("brightness") ? 0 : (int) Math.round(a.getInt("brightness") * 100 / 255.0);
                        JSONArray colorModes = a.optJSONArray("supported_color_modes");
                        dimmable = colorModes != null && !(colorModes.length() == 1 && colorModes.getString(0).equals("onoff"));
                        break;
                    case SOUND:
                        volume = a.optDouble("volume_level", 0);
                        break;
                    case CLIMATE:
                        if (!a.isNull("temperature")) target = (int) Math.round(a.getDouble("temperature"));
                        if (!a.isNull("current_temperature")) current = (int) Math.round(a.getDouble("current_temperature"));
                        minTemp = (int) Math.round(a.optDouble("min_temp", minTemp));
                        maxTemp = (int) Math.round(a.optDouble("max_temp", maxTemp));
                        modes.clear();
                        JSONArray m = a.optJSONArray("hvac_modes");
                        for (int i = 0; m != null && i < m.length(); i++) modes.add(m.getString(i));
                        break;
                    case SCRIPT:
                        break;
                }
            } catch (JSONException e) {
                throw new IllegalStateException(device.entityId + " state unreadable", e);
            }
            // While adjusting, the local value leads: a refresh can arrive before Home Assistant caught up.
            if (adjusting != this) render();
        }

        private void press() {
            if (!available) return;
            switch (device.kind) {
                case LIGHT:
                    state = isOn() ? "off" : "on";
                    if (isOn() && brightness == 0) brightness = 100;
                    call(this, "toggle", new JSONObject());
                    break;
                case SCRIPT:
                    call(this, "turn_on", new JSONObject());
                    flashDone = true;
                    main.postDelayed(() -> {
                        flashDone = false;
                        render();
                    }, 1500);
                    break;
                default:
                    adjusting = this;
                    break;
            }
            render();
        }

        /** Keys while adjusting: arrows change the value; OK or Back stop. */
        private boolean adjustKey(int keyCode, KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!down) {
                        if (swallowNextOkUp) swallowNextOkUp = false;
                        else stopAdjusting();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) stopAdjusting();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && device.kind == Home.Kind.CLIMATE) cycleMode(keyCode == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                    return true;
                default:
                    return false;
            }
        }

        private void stopAdjusting() {
            adjusting = null;
            swallowNextOkUp = false;
            render();
        }

        private void step(int delta) {
            try {
                switch (device.kind) {
                    case SOUND:
                        volume = Math.max(0, Math.min(1, Math.round((volume + delta * VOLUME_STEP) * 100) / 100.0));
                        call(this, "volume_set", new JSONObject().put("volume_level", volume));
                        break;
                    case CLIMATE:
                        target = clamp(target + delta, minTemp, maxTemp);
                        call(this, "set_temperature", new JSONObject().put("temperature", target));
                        break;
                    case LIGHT:
                        int from = isOn() ? brightness : 0;
                        brightness = clamp(from + delta * DIM_STEP, 0, 100);
                        if (brightness == 0) {
                            state = "off";
                            call(this, "turn_off", new JSONObject());
                        } else {
                            state = "on";
                            call(this, "turn_on", new JSONObject().put("brightness_pct", brightness));
                        }
                        break;
                    default:
                        break;
                }
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            render();
        }

        private void cycleMode(int delta) {
            if (modes.isEmpty()) return;
            int at = Math.max(0, modes.indexOf(state));
            state = modes.get((at + delta + modes.size()) % modes.size());
            try {
                call(this, "set_hvac_mode", new JSONObject().put("hvac_mode", state));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            render();
        }

        private boolean isOn() {
            switch (device.kind) {
                case LIGHT: return state.equals("on");
                case SOUND: return state.equals("playing");
                case CLIMATE: return available && !state.equals("off");
                default: return false;
            }
        }

        void render() {
            boolean focused = view.isFocused();
            boolean adjust = adjusting == this;
            boolean on = isOn();
            bg.setColor(focused ? Color.WHITE : on ? ON_BG : OFF_BG);
            int text = focused ? 0xFF111111 : Color.WHITE;
            name.setText(device.name.isEmpty() ? (friendlyName.isEmpty() ? device.entityId : friendlyName) : device.name);
            name.setTextColor(text);
            value.setTextColor(text);
            detail.setTextColor(focused ? 0x99111111 : 0x99FFFFFF);
            glyph.setColor(iconColor(focused, on));
            view.setAlpha(known && !available ? 0.45f : 1f);

            String v;
            String d = "";
            if (!known) {
                v = "";
            } else if (!available) {
                v = big ? "" : "Unavailable";
                d = "Unavailable";
            } else {
                switch (device.kind) {
                    case SOUND:
                        v = String.valueOf((int) Math.round(volume * 100));
                        d = adjust ? "‹ ›  volume" : state.equals("playing") ? "Playing" : "Volume";
                        break;
                    case CLIMATE:
                        v = state.equals("off") && !adjust ? "Off" : target + "°";
                        d = adjust ? "‹ › temperature   ▲▼ " + modeLabel(state) : modeLabel(state) + " · now " + current + "°";
                        break;
                    case LIGHT:
                        v = !on ? "Off" : dimmable ? brightness + "%" : "On";
                        break;
                    default:
                        v = flashDone ? "Done" : "";
                        break;
                }
            }
            value.setText(adjust ? "‹ " + v + " ›" : v);
            detail.setText(d);
        }

        private int iconColor(boolean focused, boolean on) {
            if (!on) return focused ? 0xFF1C1C1E : 0x99FFFFFF;
            switch (device.kind) {
                case LIGHT: return focused ? 0xFFD97706 : WARM_LIGHT;
                case CLIMATE:
                    if (state.equals("heat")) return focused ? 0xFFEA580C : HEAT;
                    if (state.equals("cool")) return focused ? 0xFF2563EB : COOL;
                    return focused ? 0xFF0D9488 : OTHER_MODE;
                default: return focused ? 0xFF1C1C1E : Color.WHITE;
            }
        }
    }

    private static GlyphDrawable.Kind glyphOf(Home.Kind kind) {
        switch (kind) {
            case SOUND: return GlyphDrawable.Kind.SPEAKER;
            case CLIMATE: return GlyphDrawable.Kind.FLAME;
            case LIGHT: return GlyphDrawable.Kind.BULB;
            default: return GlyphDrawable.Kind.SPARK;
        }
    }

    private static String modeLabel(String mode) {
        switch (mode) {
            case "heat": return "Heat";
            case "cool": return "Cool";
            case "fan_only": return "Fan";
            case "dry": return "Dry";
            case "auto": return "Auto";
            case "heat_cool": return "Heat/cool";
            case "off": return "Off";
            default: return mode;
        }
    }
}
