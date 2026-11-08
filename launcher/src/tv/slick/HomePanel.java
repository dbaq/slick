package tv.slick;

import android.content.Context;
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
import android.widget.ImageView;
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
    /** Climate units drop a command that comes within about a second of the last one (measured: 1s drops, 2s works). */
    private static final long CLIMATE_GAP_MS = 2000;

    // Apple Home colors: glass tiles, white when a light is on (or a tile is focused), colored icon badges.
    private static final int GLASS = 0x1FFFFFFF;
    private static final int GLASS_FOCUSED = 0x47FFFFFF;
    private static final int LIT = 0xF2FFFFFF;
    private static final int INK = 0xFF1C1C1E;
    private static final int BADGE_OFF = 0x2EFFFFFF;
    private static final int BADGE_OFF_ON_WHITE = 0x14000000;
    private static final int YELLOW = 0xFFFFD60A;
    private static final int YELLOW_INK = 0xFF3A2A00;
    private static final int BLUE = 0xFF0A84FF;
    private static final int ORANGE = 0xFFFF9F0A;
    private static final int CYAN = 0xFF32ADE6;
    private static final int GREEN = 0xFF30D158;
    private static final int PURPLE = 0xFFBF5AF2;

    private final Context activity;
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
    /** OK on a camera tile: show the door cameras big (the panel closes first). */
    private final Runnable onCameras;
    private final List<ImageView> cameraViews = new ArrayList<>();

    HomePanel(Context activity, Home home, Runnable onCameras) {
        super(activity);
        this.activity = activity;
        this.onCameras = onCameras;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        // Opaque: the home screen's own buttons sit right behind the panel's top tiles.
        bg.setColor(0xFF1A1B20);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        setBackground(bg);
        setVisibility(GONE);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        // Focused tiles grow a little; the padding leaves them room.
        content.setClipChildren(false);
        content.setClipToPadding(false);
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
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
        cameraViews.clear();
        content.removeAllViews();
        List<Tile> big = new ArrayList<>();
        List<Tile> lights = new ArrayList<>();
        List<Tile> shortcuts = new ArrayList<>();
        for (Home.Device d : home.devices) {
            Tile t = new Tile(d);
            tiles.add(t);
            (d.kind == Home.Kind.LIGHT ? lights : d.kind == Home.Kind.SCRIPT ? shortcuts : big).add(t);
        }
        grid(big, 2, 96);
        section("Lights", lights);
        section("Shortcuts", shortcuts);
        cameras();
        if (open) refresh();
    }

    void open() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        setTranslationX(dp(440));
        animate().translationX(0).setInterpolator(ease).setDuration(260).start();
        if (!tiles.isEmpty()) tiles.get(0).view.requestFocus();
        else if (!cameraViews.isEmpty()) ((View) cameraViews.get(0).getParent()).requestFocus();
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
                List<String> doorCameras = h.doorbell.isEmpty() ? new ArrayList<>() : h.doorbell.subList(1, h.doorbell.size());
                for (int i = 0; i < doorCameras.size(); i++) {
                    byte[] jpeg = h.snapshot(doorCameras.get(i).split("\\|")[0]);
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    int at = i;
                    main.post(() -> {
                        if (at < cameraViews.size()) cameraViews.get(at).setImageBitmap(b);
                    });
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
                if (t.device.kind == Home.Kind.CLIMATE) {
                    long wait = t.lastClimateCall + CLIMATE_GAP_MS - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    t.lastClimateCall = System.currentTimeMillis();
                }
                Log.i("Slick", "Home Assistant " + t.device.entityId + " " + service + " " + data);
                h.call(t.device, service, data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
        label.setTypeface(SettingsActivity.Ui.MEDIUM);
        label.setPadding(dp(4), content.getChildCount() == 0 ? 0 : dp(12), 0, dp(8));
        content.addView(label);
        grid(group, 3, 90);
    }

    /** The door cameras as live thumbnails: OK on either shows them both big. */
    private void cameras() {
        if (home.doorbell.isEmpty()) return;
        TextView label = text("Cameras", 12, 0x8CFFFFFF);
        label.setTypeface(SettingsActivity.Ui.MEDIUM);
        label.setPadding(dp(4), content.getChildCount() == 0 ? 0 : dp(12), 0, dp(8));
        content.addView(label);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (int i = 1; i < home.doorbell.size(); i++) {
            FrameLayout tile = new FrameLayout(activity);
            tile.setFocusable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(GLASS);
            tile.setForeground(bg);
            tile.setClipToOutline(true);
            tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackgroundColor(0xFF000000);
            tile.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            cameraViews.add(picture);
            tile.setOnFocusChangeListener((v, focused) -> {
                // The picture stays clear; focus is a white ring and a slight lift, like the other tiles.
                bg.setColor(Color.TRANSPARENT);
                bg.setStroke(focused ? dp(3) : 0, Color.WHITE);
                float scale = focused ? 1.05f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setInterpolator(ease).setDuration(160).start();
                v.setTranslationZ(focused ? dp(6) : 0);
            });
            bg.setColor(Color.TRANSPARENT);
            tile.setOnClickListener(v -> onCameras.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(100), 1f);
            if (i > 1) lp.leftMargin = dp(10);
            row.addView(tile, lp);
        }
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void grid(List<Tile> group, int columns, int heightDp) {
        for (int i = 0; i < group.size(); i += columns) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            // A lone big tile takes the whole row; small tiles keep their size and leave a gap.
            boolean lone = columns == 2 && i + 1 == group.size();
            for (int c = 0; c < (lone ? 1 : columns); c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
                if (c > 0) lp.leftMargin = dp(10);
                row.addView(i + c < group.size() ? group.get(i + c).view : new Space(activity), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (content.getChildCount() > 0 && !(content.getChildAt(content.getChildCount() - 1) instanceof TextView)) {
                rp.topMargin = dp(10);
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

    /** "Foyer Light Switch" reads "Foyer", "Kitchen Main Lights" reads "Kitchen Main": the tile already says it's a light. */
    static String shortName(String name) {
        String n = name.trim();
        for (String tail : new String[] {" Light Switch", " Lights", " Light", " Switch"}) {
            if (n.length() > tail.length() && n.toLowerCase().endsWith(tail.toLowerCase())) {
                return n.substring(0, n.length() - tail.length()).trim();
            }
        }
        return n;
    }

    private static boolean sameDevices(Home a, Home b) {
        if (a.devices.size() != b.devices.size() || !a.doorbell.equals(b.doorbell)) return false;
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
        private final GradientDrawable badge = new GradientDrawable();
        private final TextView name;
        private final TextView value;
        private final TextView detail;
        private final GradientDrawable bg = new GradientDrawable();
        private final boolean big;
        /** Sound only: the volume as a thin bar under the name. */
        private final GradientDrawable track = new GradientDrawable();
        private final GradientDrawable fill = new GradientDrawable();
        private View fillView;

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
        /** When the network thread last sent this climate unit a command. */
        volatile long lastClimateCall;
        /** A climate target picked with Left/Right, sent when OK confirms it. */
        private boolean targetChanged;

        Tile(Home.Device device) {
            this.device = device;
            big = device.kind == Home.Kind.SOUND || device.kind == Home.Kind.CLIMATE;
            view = new LinearLayout(activity);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setFocusable(true);
            view.setPadding(dp(12), dp(12), dp(12), dp(11));
            bg.setCornerRadius(dp(16));
            view.setBackground(bg);

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            glyph = new GlyphDrawable(glyphOf(device.kind));
            FrameLayout chip = new FrameLayout(activity);
            badge.setShape(GradientDrawable.OVAL);
            chip.setBackground(badge);
            View icon = new View(activity);
            icon.setBackground(glyph);
            chip.addView(icon, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
            top.addView(chip, new LinearLayout.LayoutParams(dp(30), dp(30)));
            value = text("", big ? 20 : 13, Color.WHITE);
            value.setTypeface(SettingsActivity.Ui.MEDIUM);
            value.setGravity(Gravity.END);
            top.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
            view.addView(top, new LinearLayout.LayoutParams(-1, -2));
            // The badge keeps its size; the room left over goes between it and the name.
            view.addView(new Space(activity), new LinearLayout.LayoutParams(-1, 0, 1f));

            name = text("", 13, Color.WHITE);
            name.setTypeface(SettingsActivity.Ui.MEDIUM);
            view.addView(name);
            detail = text("", 11, 0x99FFFFFF);
            view.addView(detail);
            if (device.kind == Home.Kind.SOUND) {
                FrameLayout bar = new FrameLayout(activity);
                track.setCornerRadius(dp(2));
                bar.setBackground(track);
                fillView = new View(activity);
                fill.setCornerRadius(dp(2));
                fillView.setBackground(fill);
                fillView.setPivotX(0);
                bar.addView(fillView, new FrameLayout.LayoutParams(-1, -1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(3));
                lp.topMargin = dp(6);
                view.addView(bar, lp);
                detail.setVisibility(GONE);
            }

            view.setOnFocusChangeListener((v, focused) -> {
                if (!focused && adjusting == this) {
                    adjusting = null;
                    targetChanged = false;
                }
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
            // While adjusting, the local value leads: a refresh can land between two presses, before Home
            // Assistant caught up, and must not pull the value back.
            if (adjusting == this) return;
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
            render();
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
                        else stopAdjusting(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) stopAdjusting(false);
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

        /** OK confirms a new climate target; Back drops it and the next refresh shows the real one. */
        private void stopAdjusting(boolean confirm) {
            if (targetChanged && confirm) {
                try {
                    call(this, "set_temperature", new JSONObject().put("temperature", target));
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            targetChanged = false;
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
                        targetChanged = true;
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
            boolean lightOn = device.kind == Home.Kind.LIGHT && on;
            // White when a light is on, or when the focus is on a tile that isn't a light; a focused light
            // that's off brightens its glass and gets a white ring instead.
            boolean white = lightOn || (focused && device.kind != Home.Kind.LIGHT);
            bg.setColor(white ? LIT : focused ? GLASS_FOCUSED : GLASS);
            bg.setStroke(focused ? dp(2) : 0, focused && !white ? Color.WHITE : Color.TRANSPARENT);
            float scale = focused ? 1.05f : 1f;
            view.animate().scaleX(scale).scaleY(scale).setInterpolator(ease).setDuration(160).start();
            view.setTranslationZ(focused ? dp(6) : 0);
            int text = white ? INK : Color.WHITE;
            name.setText(shortName(device.name.isEmpty() ? (friendlyName.isEmpty() ? device.entityId : friendlyName) : device.name));
            name.setTextColor(text);
            value.setTextColor(text);
            detail.setTextColor(white ? 0x991C1C1E : 0x99FFFFFF);
            paintBadge(white, on);
            if (fillView != null) {
                track.setColor(white ? 0x1F000000 : 0x33FFFFFF);
                fill.setColor(white ? INK : Color.WHITE);
                fillView.setScaleX((float) volume);
            }
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
                        break;
                    case CLIMATE:
                        v = state.equals("off") && !adjust ? "Off" : target + "°";
                        d = adjust ? "‹ › temperature   ▲▼ " + modeLabel(state) : modeLabel(state) + " · " + current + "° now";
                        break;
                    case LIGHT:
                        // The tile's color says on or off; only the brightness needs words.
                        v = adjust ? brightness + "%" : "";
                        d = on && dimmable && !adjust ? brightness + "%" : "";
                        break;
                    default:
                        v = flashDone ? "Done" : "";
                        break;
                }
            }
            value.setText(adjust ? "‹ " + v + " ›" : v);
            detail.setText(d);
            if (device.kind != Home.Kind.SOUND) detail.setVisibility(d.isEmpty() ? GONE : VISIBLE);
        }

        /** The round icon badge: colored by what the device is doing, gray when it's off. */
        private void paintBadge(boolean white, boolean on) {
            int color;
            int ink = Color.WHITE;
            switch (device.kind) {
                case LIGHT:
                    color = on ? YELLOW : 0;
                    ink = YELLOW_INK;
                    break;
                case SOUND:
                    color = available ? BLUE : 0;
                    break;
                case CLIMATE:
                    glyph.setKind(state.equals("cool") ? GlyphDrawable.Kind.SNOW : GlyphDrawable.Kind.FLAME);
                    color = !on ? 0 : state.equals("heat") ? ORANGE : state.equals("cool") ? CYAN : GREEN;
                    break;
                default:
                    color = flashDone ? GREEN : PURPLE;
                    break;
            }
            if (color == 0) {
                badge.setColor(white ? BADGE_OFF_ON_WHITE : BADGE_OFF);
                glyph.setColor(white ? INK : Color.WHITE);
            } else {
                badge.setColor(color);
                glyph.setColor(ink);
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
