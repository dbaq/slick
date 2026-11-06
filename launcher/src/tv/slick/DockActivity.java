package tv.slick;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Which installed apps sit on the dock (up to {@link #MAX}), in which order. OK adds or removes an app;
 * Left/Right moves an app on the dock earlier or later.
 */
public class DockActivity extends Activity {

    /** What fits across the screen at the dock's tile size. */
    static final int MAX = 6;
    private static final String HINT = "OK adds or removes an app (up to " + MAX + ").  ‹ ›  moves it along the dock.";

    private Prefs prefs;
    private List<String> dock;
    private final TreeMap<String, String> others = new TreeMap<>();
    private LinearLayout list;
    private TextView hint;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        dock = prefs.dock();

        PackageManager pm = getPackageManager();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(leanback, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || dock.contains(pkg)) continue;
            others.put(r.loadLabel(pm).toString(), pkg);
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0B0B0F);
        page.setPadding(dp(120), dp(48), dp(120), dp(24));
        page.addView(SettingsActivity.Ui.text(this, "Apps on the dock", 34, SettingsActivity.Ui.MEDIUM, Color.WHITE));
        hint = SettingsActivity.Ui.text(this, HINT, 14, SettingsActivity.Ui.REGULAR, 0x80FFFFFF);
        hint.setPadding(0, dp(8), 0, dp(16));
        page.addView(hint);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        page.addView(scroll);
        setContentView(page);
        render(dock.isEmpty() ? null : dock.get(0));
    }

    /** Rebuilds the list (dock first, in order; then the rest A-Z), keeping focus on {@code focusPkg}. */
    private void render(String focusPkg) {
        list.removeAllViews();
        PackageManager pm = getPackageManager();
        List<String> order = new ArrayList<>(dock);
        order.addAll(others.values());
        View focus = null;
        for (String pkg : order) {
            View row = row(pm, pkg, dock.contains(pkg));
            list.addView(row);
            if (pkg.equals(focusPkg)) focus = row;
        }
        (focus != null ? focus : list.getChildAt(0)).requestFocus();
    }

    private View row(PackageManager pm, String pkg, boolean onDock) {
        String name;
        Drawable art;
        try {
            name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            art = pkg.equals("org.smarttube.beta") ? new YouTubeBanner() : pm.getApplicationBanner(pkg);
            if (art == null) art = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(dp(16), dp(10), dp(20), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(bg);

        ImageView image = new ImageView(this);
        image.setImageDrawable(art);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(6));
            }
        });
        image.setClipToOutline(true);
        image.setAlpha(onDock ? 1f : 0.5f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(64), dp(36));
        ip.rightMargin = dp(18);
        row.addView(image, ip);

        TextView label = SettingsActivity.Ui.text(this, name, 17, SettingsActivity.Ui.REGULAR,
                onDock ? Color.WHITE : 0x99FFFFFF);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView state = SettingsActivity.Ui.text(this, onDock ? "On the dock" : "", 15,
                SettingsActivity.Ui.REGULAR, 0x99FFFFFF);
        row.addView(state);

        row.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Color.WHITE : Color.TRANSPARENT);
            label.setTextColor(focused ? 0xFF111111 : onDock ? Color.WHITE : 0x99FFFFFF);
            state.setTextColor(focused ? 0xFF111111 : 0x99FFFFFF);
        });
        row.setOnClickListener(v -> toggle(pkg, name));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || !dock.contains(pkg)) return false;
            int delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (delta == 0) return false;
            int from = dock.indexOf(pkg);
            int to = from + delta;
            if (to < 0 || to >= dock.size()) return true;
            dock.remove(from);
            dock.add(to, pkg);
            save(pkg);
            return true;
        });
        return row;
    }

    private void toggle(String pkg, String name) {
        if (dock.contains(pkg)) {
            // An empty dock would leave Home with nothing to focus.
            if (dock.size() == 1) return;
            dock.remove(pkg);
            others.put(name, pkg);
        } else {
            if (dock.size() == MAX) {
                hint.setText("The dock holds " + MAX + " apps. Remove one first, then add " + name + ".");
                hint.setTextColor(0xFFFBBF24);
                return;
            }
            dock.add(pkg);
            others.values().remove(pkg);
        }
        hint.setText(HINT);
        hint.setTextColor(0x80FFFFFF);
        save(pkg);
    }

    private void save(String focusPkg) {
        prefs.setDock(dock);
        render(focusPkg);
    }

    private int dp(int v) {
        return SettingsActivity.Ui.dp(this, v);
    }
}
