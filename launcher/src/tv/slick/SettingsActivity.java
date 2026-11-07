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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Slick Settings: picks schedule, look, dock apps, and the way to Fire TV's own settings. */
public class SettingsActivity extends Activity {

    private static final int HALF_HOUR = 30;

    private static final String TRAKT_ABOUT = "Evening picks from your Trakt history: next episodes, new seasons and your watchlist.";
    /** A second OK within this long disconnects Trakt. */
    private static final long CONFIRM_MS = 5000;

    private Prefs prefs;
    private LinearLayout list;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Trakt trakt;
    /** The sign-in under way, or null. */
    private volatile Thread signIn;
    private long confirmUntil;

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
        traktRow();
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

    /**
     * Trakt sign-in: OK shows a code to type at trakt.tv/activate on a phone, and the row says "Connected"
     * once it's approved. When connected, OK twice disconnects this TV.
     */
    private void traktRow() {
        trakt = new Trakt(getFilesDir());
        LinearLayout row = row("Trakt", TRAKT_ABOUT);
        TextView about = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        TextView v = Ui.text(this, trakt.connected() ? "Connected" : "Not connected", 17, Ui.REGULAR, 0x99FFFFFF);
        row.addView(v);
        row.setOnClickListener(view -> {
            if (signIn != null) return;
            if (trakt.connected()) {
                if (System.currentTimeMillis() > confirmUntil) {
                    confirmUntil = System.currentTimeMillis() + CONFIRM_MS;
                    v.setText("OK again to disconnect");
                    return;
                }
                trakt.disconnect();
                v.setText("Not connected");
                return;
            }
            v.setText("Getting a code…");
            Thread t = new Thread(() -> signIn(v, about));
            signIn = t;
            t.start();
        });
        focusStyle(row, v);
    }

    /** Runs on its own thread: get a code, show it, wait for it to be approved or to run out. */
    private void signIn(TextView v, TextView about) {
        try {
            Trakt.Code code = trakt.start();
            String site = code.url.replaceFirst("^https?://", "");
            main.post(() -> {
                v.setText(code.userCode);
                v.setTypeface(Ui.MEDIUM);
                about.setText("On your phone, open " + site + " and enter this code.");
            });
            while (signIn == Thread.currentThread() && System.currentTimeMillis() < code.expiresAtMs) {
                Thread.sleep(code.intervalS * 1000L);
                if (trakt.poll(code)) {
                    main.post(() -> done(v, about, "Connected"));
                    return;
                }
            }
            main.post(() -> done(v, about, "Code expired · OK for a new one"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w("Slick", "Trakt sign-in failed", e);
            main.post(() -> done(v, about, "Can't reach Trakt · OK to try again"));
        }
    }

    private void done(TextView v, TextView about, String result) {
        signIn = null;
        v.setText(result);
        v.setTypeface(Ui.REGULAR);
        about.setText(TRAKT_ABOUT);
    }

    @Override
    protected void onDestroy() {
        Thread t = signIn;
        signIn = null;
        if (t != null) t.interrupt();
        super.onDestroy();
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
