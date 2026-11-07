package tv.slick;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Which Home Assistant devices sit on this TV's panel (up to {@link Home#MAX_DEVICES}), in which order.
 * OK adds or removes a device; Left/Right moves it earlier or later. Only lights, climate, speakers and
 * scripts are listed: Slick never controls anything else.
 */
public class HomeControlsActivity extends Activity {

    private static final String HINT = "OK adds or removes a device (up to " + Home.MAX_DEVICES + ").  ‹ ›  moves it on the panel.";

    private Home home;
    private final List<Home.Device> chosen = new ArrayList<>();
    /** Every controllable entity: id to Home Assistant's name. */
    private final TreeMap<String, String> names = new TreeMap<>();
    private LinearLayout list;
    private TextView hint;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        home = Home.load(this);
        if (home == null) throw new IllegalStateException("Home Assistant isn't set up on this TV");
        chosen.addAll(home.devices);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0B0B0F);
        page.setPadding(dp(120), dp(48), dp(120), dp(24));
        page.addView(SettingsActivity.Ui.text(this, "Home controls", 34, SettingsActivity.Ui.MEDIUM, Color.WHITE));
        hint = SettingsActivity.Ui.text(this, "Loading devices from Home Assistant…", 14, SettingsActivity.Ui.REGULAR, 0x80FFFFFF);
        hint.setPadding(0, dp(8), 0, dp(16));
        page.addView(hint);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        page.addView(scroll);
        setContentView(page);

        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                TreeMap<String, String> found = new TreeMap<>();
                for (JSONObject s : home.controllable()) {
                    // Dead entities (an old TV's leftovers) only clutter the list, unless they're already on the panel.
                    boolean dead = s.getString("state").equals("unavailable");
                    if (dead && indexOf(s.getString("entity_id")) < 0) continue;
                    found.put(s.getString("entity_id"), s.getJSONObject("attributes").optString("friendly_name"));
                }
                main.post(() -> {
                    names.putAll(found);
                    hint.setText(HINT);
                    render(chosen.isEmpty() ? null : chosen.get(0).entityId);
                });
            } catch (IOException | JSONException e) {
                Log.w("Slick", "Home Assistant devices unavailable", e);
                main.post(() -> {
                    hint.setText("Can't reach Home Assistant. Check the TV's network, then open this again.");
                    hint.setTextColor(0xFFFBBF24);
                });
            }
        }).start();
    }

    /** Panel devices first, in order; then every other device A-Z by name. Focus stays on {@code focusId}. */
    private void render(String focusId) {
        list.removeAllViews();
        View focus = null;
        List<String> onPanel = new ArrayList<>();
        for (Home.Device d : chosen) {
            onPanel.add(d.entityId);
            View row = row(d.entityId, label(d), true);
            list.addView(row);
            if (d.entityId.equals(focusId)) focus = row;
        }
        TreeMap<String, String> others = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String id : names.keySet()) {
            if (!onPanel.contains(id)) others.put(nameOf(id) + "\u0000" + id, id);
        }
        for (String id : others.values()) {
            View row = row(id, nameOf(id), false);
            list.addView(row);
            if (id.equals(focusId)) focus = row;
        }
        if (focus == null && list.getChildCount() > 0) focus = list.getChildAt(0);
        if (focus != null) focus.requestFocus();
    }

    private View row(String id, String name, boolean onPanel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(bg);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView label = SettingsActivity.Ui.text(this, name, 17, SettingsActivity.Ui.REGULAR, onPanel ? Color.WHITE : 0x99FFFFFF);
        text.addView(label);
        TextView kind = SettingsActivity.Ui.text(this, kindLabel(Home.Kind.of(id)), 13, SettingsActivity.Ui.REGULAR, 0x80FFFFFF);
        text.addView(kind);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView state = SettingsActivity.Ui.text(this, onPanel ? "On the panel" : "", 15, SettingsActivity.Ui.REGULAR, 0x99FFFFFF);
        row.addView(state);

        row.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Color.WHITE : Color.TRANSPARENT);
            label.setTextColor(focused ? 0xFF111111 : onPanel ? Color.WHITE : 0x99FFFFFF);
            kind.setTextColor(focused ? 0x99111111 : 0x80FFFFFF);
            state.setTextColor(focused ? 0xFF111111 : 0x99FFFFFF);
        });
        row.setOnClickListener(v -> toggle(id));
        row.setOnKeyListener((v, keyCode, event) -> {
            int at = indexOf(id);
            if (event.getAction() != KeyEvent.ACTION_DOWN || at < 0) return false;
            int delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (delta == 0) return false;
            int to = at + delta;
            if (to < 0 || to >= chosen.size()) return true;
            chosen.add(to, chosen.remove(at));
            save(id);
            return true;
        });
        return row;
    }

    private void toggle(String id) {
        int at = indexOf(id);
        if (at >= 0) {
            chosen.remove(at);
        } else {
            if (chosen.size() == Home.MAX_DEVICES) {
                hint.setText("The panel holds " + Home.MAX_DEVICES + " devices. Remove one first, then add " + nameOf(id) + ".");
                hint.setTextColor(0xFFFBBF24);
                return;
            }
            chosen.add(new Home.Device(id, ""));
        }
        hint.setText(HINT);
        hint.setTextColor(0x80FFFFFF);
        save(id);
    }

    private void save(String focusId) {
        home = home.withDevices(new ArrayList<>(chosen));
        home.save(this);
        render(focusId);
    }

    private int indexOf(String id) {
        for (int i = 0; i < chosen.size(); i++) if (chosen.get(i).entityId.equals(id)) return i;
        return -1;
    }

    private String label(Home.Device d) {
        return d.name.isEmpty() ? nameOf(d.entityId) : d.name;
    }

    private String nameOf(String id) {
        String n = names.get(id);
        return n == null || n.isEmpty() ? id : n;
    }

    private static String kindLabel(Home.Kind kind) {
        switch (kind) {
            case SOUND: return "Volume";
            case CLIMATE: return "Climate";
            case LIGHT: return "Light";
            default: return "Shortcut";
        }
    }

    private int dp(int v) {
        return SettingsActivity.Ui.dp(this, v);
    }
}
