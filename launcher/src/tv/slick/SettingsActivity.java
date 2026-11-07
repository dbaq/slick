package tv.slick;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Slick Settings: picks schedule, look, dock apps, and the way to Fire TV's own settings. */
public class SettingsActivity extends Activity {

    private static final int HALF_HOUR = 30;

    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0B0B0F);
        page.setPadding(Ui.dp(this, 120), Ui.dp(this, 48), Ui.dp(this, 120), Ui.dp(this, 24));
        page.addView(Ui.text(this, "Settings", 34, Ui.MEDIUM, Color.WHITE));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 20), 0, 0);
        list.setClipChildren(false);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.addView(list);
        page.addView(scroll);

        section("Picks");
        choice("Daytime", "Who the picks are for during the day.", () -> prefs.dayAudience().label,
                d -> prefs.setDayAudience(prefs.dayAudience().next(d)));
        choice("Evening", "Who the picks are for in the evening, between the two times below.", () -> prefs.eveningAudience().label,
                d -> prefs.setEveningAudience(prefs.eveningAudience().next(d)));
        choice("Evening starts", "When the evening picks take over.", () -> Prefs.clock(prefs.eveningStart()),
                d -> prefs.setEveningStart(wrap(prefs.eveningStart() + d * HALF_HOUR)));
        choice("Evening ends", "When the daytime picks come back.", () -> Prefs.clock(prefs.eveningEnd()),
                d -> prefs.setEveningEnd(wrap(prefs.eveningEnd() + d * HALF_HOUR)));
        choice("Picks change every", "How long each pick stays on screen before the next one. Off keeps it until you move.",
                () -> prefs.rotateSeconds() == 0 ? "Off" : prefs.rotateSeconds() + " seconds",
                d -> prefs.setRotateSeconds(nextRotation(prefs.rotateSeconds(), d)));
        choice("Seasonal picks", "Halloween and Christmas titles for the " + Season.LEAD_DAYS + " days before each.",
                () -> prefs.seasonal() ? "On" : "Off",
                d -> prefs.setSeasonal(!prefs.seasonal()));
        hiddenRow();
        note("Kids: G movies, TV-Y and TV-G shows. Family: up to PG and TV-PG. "
                + "Adults: every rating, without titles made for little kids.");

        section("Look");
        choice("Theme", "Auto is light by day and dark after sunset.", () -> label(prefs.theme()), d -> prefs.setTheme(next(prefs.theme(), d)));

        section("Home screen");
        action("Apps on the dock", "Pick up to " + DockActivity.MAX + " apps and their order.", new Intent(this, DockActivity.class));
        // Settings.ACTION_SETTINGS detours through Amazon's home screen, which HomeGuard bounces.
        if (Home.load(this) != null) {
            action("Home controls", "Lights, climate and sound on the panel the house button opens.",
                    new Intent(this, HomeControlsActivity.class));
        }
        action("Fire TV settings", "Amazon's settings: network, display, sound, remotes and apps.", new Intent().setClassName(
                "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"));

        setContentView(page);
        list.getChildAt(1).requestFocus();
    }

    private void section(String title) {
        TextView t = Ui.text(this, title.toUpperCase(), 13, Ui.MEDIUM, 0x80FFFFFF);
        t.setLetterSpacing(0.12f);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 22), 0, Ui.dp(this, 8));
        list.addView(t);
    }

    private void note(String text) {
        TextView t = Ui.text(this, text, 13, Ui.REGULAR, 0x80FFFFFF);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        list.addView(t);
    }

    /** A row whose value Left/Right changes. */
    private void choice(String label, String description, Supplier<String> value, IntConsumer change) {
        LinearLayout row = row(label, description);
        TextView v = Ui.text(this, "", 17, Ui.REGULAR, Color.WHITE);
        Runnable refresh = () -> v.setText("‹  " + value.get() + "  ›");
        refresh.run();
        row.addView(v);
        row.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (delta == 0) return false;
            change.accept(delta);
            refresh.run();
            return true;
        });
        row.setOnClickListener(view -> {
            change.accept(1);
            refresh.run();
        });
        focusStyle(row, v);
    }

    /** Titles hidden with "Hold OK"; OK brings them all back. */
    private void hiddenRow() {
        LinearLayout row = row("Hidden titles", "Titles you hid by holding OK on a pick. OK here brings them all back.");
        TextView v = Ui.text(this, "", 17, Ui.REGULAR, 0x99FFFFFF);
        Runnable refresh = () -> {
            int n = prefs.hidden().size();
            v.setText(n == 0 ? "None" : String.valueOf(n));
        };
        refresh.run();
        row.addView(v);
        row.setOnClickListener(view -> {
            prefs.clearHidden();
            refresh.run();
        });
        focusStyle(row, v);
    }

    /** A row that opens another screen. */
    private void action(String label, String description, Intent target) {
        LinearLayout row = row(label, description);
        TextView chevron = Ui.text(this, "›", 20, Ui.REGULAR, 0x99FFFFFF);
        row.addView(chevron);
        row.setOnClickListener(view -> startActivity(target));
        focusStyle(row, chevron);
    }

    /** A row with its name, a short description under it, and room for a value on the right. */
    private LinearLayout row(String label, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(this, label, 17, Ui.REGULAR, Color.WHITE));
        TextView about = Ui.text(this, description, 13, Ui.REGULAR, 0x80FFFFFF);
        about.setPadding(0, Ui.dp(this, 2), Ui.dp(this, 24), 0);
        text.addView(about);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 14));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 4);
        list.addView(row, lp);
        return row;
    }

    /** Focused rows turn solid white with dark text, like the home screen's buttons. */
    private void focusStyle(LinearLayout row, TextView value) {
        LinearLayout text = (LinearLayout) row.getChildAt(0);
        TextView name = (TextView) text.getChildAt(0);
        TextView about = (TextView) text.getChildAt(1);
        int valueColor = value.getCurrentTextColor();
        row.setOnFocusChangeListener((v, focused) -> {
            ((GradientDrawable) v.getBackground()).setColor(focused ? Color.WHITE : Color.TRANSPARENT);
            name.setTextColor(focused ? 0xFF111111 : Color.WHITE);
            about.setTextColor(focused ? 0x99111111 : 0x80FFFFFF);
            value.setTextColor(focused ? 0xFF111111 : valueColor);
        });
    }

    private static int nextRotation(int seconds, int delta) {
        int[] all = Prefs.ROTATE_SECONDS;
        int at = 0;
        while (all[at] != seconds) at++;
        return all[(at + delta + all.length) % all.length];
    }

    private static int wrap(int minutes) {
        return ((minutes % (24 * 60)) + 24 * 60) % (24 * 60);
    }

    private static Prefs.Theme next(Prefs.Theme t, int delta) {
        Prefs.Theme[] all = Prefs.Theme.values();
        return all[(t.ordinal() + delta + all.length) % all.length];
    }

    private static String label(Prefs.Theme t) {
        switch (t) {
            case AUTO: return "Auto (sunset)";
            case LIGHT: return "Light";
            default: return "Dark";
        }
    }

    /** Shared text and size helpers for Slick's screens. */
    static final class Ui {
        static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
        static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        static TextView text(Activity a, String s, int sp, Typeface face, int color) {
            TextView t = new TextView(a);
            t.setText(s);
            t.setTextSize(sp);
            t.setTypeface(face);
            t.setTextColor(color);
            return t;
        }

        static int dp(Activity a, int v) {
            return Math.round(v * a.getResources().getDisplayMetrics().density);
        }
    }
}
