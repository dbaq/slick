package tv.slick;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV-style home. The backdrop shows what's trending on the focused dock app (the "top shelf");
 * Up from the dock opens that pick wide, with Left/Right through every pick and OK to play it.
 */
public class HomeActivity extends Activity {

    /** Shown with YouTube's look: it is the YouTube app here, just without the ads. */
    private static final String SMARTTUBE = "org.smarttube.beta";

    /** How far the dock (and the pick above it) slide down in hero mode, leaving the dock peeking. */
    private static final int HERO_DROP = 124;
    private static final long THEME_CHECK_MS = 60_000;
    private static final int DOTS = 7;

    private static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotate = this::rotate;
    private final Runnable themeCheck = this::themeCheck;

    private PickStore store;
    private Prefs prefs;
    /** Who the picks are for right now: follows the Settings schedule. */
    private Audience audience;
    /** The holiday season now (null outside one, or when Settings turns seasonal picks off). */
    private Season season;
    private boolean holidayDay;
    private List<String> dockApps;
    private List<Pick> picks = new ArrayList<>();
    private final Map<Pick.Service, List<Pick>> byService = new EnumMap<>(Pick.Service.class);

    private FrameLayout root;
    private ImageView backFront;
    private ImageView backRear;
    private LinearLayout dock;
    private final List<View> tiles = new ArrayList<>();
    private final Map<View, Bitmap> glowOf = new HashMap<>();
    private View lastTile;

    private LinearLayout info;
    /** Holds {@link #info}: the slot slides for hero mode while the info itself fades between picks. */
    private FrameLayout infoSlot;
    private FrameLayout titleBox;
    private ImageView logoView;
    private TextView titleText;
    private TextView metaText;
    private TextView overviewText;
    private LinearLayout play;
    /** "Watch on Netflix": where OK takes the focused pick. */
    private TextView playLabel;
    /** Pill pagination under the button: where Left/Right are in the picks. */
    private LinearLayout dots;

    private View wifiButton;
    private final List<View> glassButtons = new ArrayList<>();
    private final List<GlyphDrawable> glassGlyphs = new ArrayList<>();
    private Boolean day;

    /** What the backdrop shows: a pick list and a position in it. */
    private List<Pick> showing = new ArrayList<>();
    private int index;
    private Pick current;
    private boolean hero;
    private int showSeq;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new PickStore(this);
        prefs = new Prefs(this);
        long now = System.currentTimeMillis();
        audience = prefs.audienceAt(now);
        season = prefs.seasonal() ? Season.at(now) : null;
        holidayDay = season != null && season.isFullDay(now);
        dockApps = prefs.dock();

        root = new FrameLayout(this);
        root.setClipChildren(false);

        backRear = backdropLayer();
        backFront = backdropLayer();
        root.addView(backRear, new FrameLayout.LayoutParams(-1, -1));
        root.addView(backFront, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildClock(), frame(-2, -2, Gravity.TOP | Gravity.START, 64, 36, 0, 0));
        root.addView(buildButtons(), frame(-2, -2, Gravity.TOP | Gravity.END, 0, 44, 64, 0));
        root.addView(buildInfo(), frame(480, -2, Gravity.BOTTOM | Gravity.START, 64, 0, 0, 204));
        root.addView(buildDock(), frame(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 40));

        setContentView(root);
        lastTile = tiles.get(0);
        lastTile.requestFocus();

        loadPicks(true);
    }

    private void loadPicks(boolean deliverCached) {
        Audience forAudience = audience;
        Season forSeason = season;
        boolean forDay = holidayDay;
        store.load(forAudience, forSeason, forDay, prefs.hidden(), fresh -> {
            if (forAudience == audience && forSeason == season && forDay == holidayDay) onPicks(fresh);
        }, deliverCached);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardKeeper.ensure(this);
        if (!prefs.dock().equals(dockApps)) {
            recreate();
            return;
        }
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkWatch);
        themeCheck();
        loadPicks(false);
        scheduleRotate();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(themeCheck);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkWatch);
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        scheduleRotate();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Home has nowhere to go back to; Back from the wide pick returns to the dock.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (hero) lastTile.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Picks -------------------------------------------------------------------------------------

    private void onPicks(List<Pick> fresh) {
        // Each TV has its own apps: a pick for a service this one doesn't have can't be played here.
        Set<String> hidden = prefs.hidden();
        picks = new ArrayList<>();
        for (Pick p : fresh) {
            if (launchIntent(p.service.pkg) != null && !hidden.contains(Prefs.key(p))) picks.add(p);
        }
        byService.clear();
        for (Pick p : picks) {
            List<Pick> group = byService.get(p.service);
            if (group == null) byService.put(p.service, group = new ArrayList<>());
            group.add(p);
        }
        if (hero) enterHero();
        else onTileFocused(lastTile);
    }

    private List<Pick> picksFor(View tile) {
        Pick.Service service = Pick.Service.ofPackage((String) tile.getTag());
        List<Pick> group = service == null ? null : byService.get(service);
        return group == null ? new ArrayList<>() : group;
    }

    private void onTileFocused(View tile) {
        lastTile = tile;
        List<Pick> group = picksFor(tile);
        play.setFocusable(!picks.isEmpty());
        if (group.isEmpty()) {
            showing = group;
            current = null;
            showSeq++;
            crossfade(glowOf.get(tile));
            info.animate().alpha(0f).setDuration(200).start();
            return;
        }
        if (showing != group) {
            showing = group;
            index = Math.max(0, group.indexOf(current));
        }
        show(showing.get(index));
    }

    private void rotate() {
        if (showing.size() > 1 && current != null) {
            index = (index + 1) % showing.size();
            show(showing.get(index));
        }
        scheduleRotate();
    }

    private void scheduleRotate() {
        handler.removeCallbacks(rotate);
        int seconds = prefs.rotateSeconds();
        if (seconds > 0) handler.postDelayed(rotate, seconds * 1000L);
    }

    private void step(int delta) {
        if (showing.isEmpty()) return;
        index = (index + delta + showing.size()) % showing.size();
        show(showing.get(index));
    }

    private void show(Pick p) {
        boolean same = p == current;
        current = p;
        int seq = ++showSeq;
        if (!same) {
            store.backdrop(p, b -> {
                if (seq == showSeq) crossfade(b);
            });
            Pick next = showing.get((index + 1) % showing.size());
            store.backdrop(next, b -> { });
        }
        info.animate().cancel();
        info.animate().alpha(0f).setDuration(same ? 0 : 140).withEndAction(() -> {
            if (seq != showSeq) return;
            fillInfo(p, seq);
            info.animate().alpha(1f).setDuration(260).start();
        }).start();
        updateDots();
    }

    private void fillInfo(Pick p, int seq) {
        titleText.setText(p.title);
        titleText.setVisibility(View.VISIBLE);
        logoView.setVisibility(View.INVISIBLE);
        if (!p.logo.isEmpty()) {
            store.logo(p, b -> {
                if (seq != showSeq) return;
                logoView.setImageBitmap(b);
                logoView.setVisibility(View.VISIBLE);
                titleText.setVisibility(View.INVISIBLE);
            });
        }
        if (hero) {
            metaText.setText(p.elsewhere.isEmpty() ? p.meta : p.meta + " · on " + p.elsewhere);
            playLabel.setText((p.service == Pick.Service.STREMIO ? "Watch in " : "Watch on ") + p.service.label);
            overviewText.setText(p.overview);
        } else {
            String kind = p.holiday && season != null ? season.label + " pick" : "Trending";
            metaText.setText(p.service == Pick.Service.STREMIO
                    ? kind + " · " + p.where() : kind + " on " + p.service.label);
        }
    }

    private void open(Pick p) {
        Intent intent;
        if (p.service == Pick.Service.STREMIO) {
            intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("stremio:///detail/" + (p.series ? "series" : "movie") + "/" + p.imdbId))
                    .setPackage(p.service.pkg);
        } else if (p.link.isEmpty()) {
            intent = launchIntent(p.service.pkg);
        } else {
            // A fresh task: an app already running ignores a link handed to its old one.
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(p.link)).setPackage(p.service.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // Netflix's TV app only follows links that say they come from a partner launcher.
            if (p.service == Pick.Service.NETFLIX) intent.putExtra("source", "30");
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---- Hero (the wide pick) ----------------------------------------------------------------------

    private void enterHero() {
        hero = true;
        int at = current == null ? 0 : Math.max(0, picks.indexOf(current));
        showing = picks;
        index = at;
        overviewText.setVisibility(View.VISIBLE);
        dots.setVisibility(View.VISIBLE);
        play.animate().alpha(1f).setDuration(220).start();
        titleBox.animate().scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(300).start();
        infoSlot.animate().translationY(dp(HERO_DROP)).setInterpolator(ease).setDuration(300).start();
        dock.animate().translationY(dp(HERO_DROP)).alpha(0.35f).setInterpolator(ease).setDuration(300).start();
        show(showing.get(index));
    }

    private void exitHero() {
        hero = false;
        overviewText.setVisibility(View.GONE);
        dots.setVisibility(View.GONE);
        play.animate().alpha(0f).setDuration(150).start();
        titleBox.animate().scaleX(0.7f).scaleY(0.7f).setInterpolator(ease).setDuration(300).start();
        infoSlot.animate().translationY(0).setInterpolator(ease).setDuration(300).start();
        dock.animate().translationY(0).alpha(1f).setInterpolator(ease).setDuration(300).start();
        onTileFocused(lastTile);
    }

    /**
     * At most {@link #DOTS} dots, a window that follows the current pick: it is a pill, its neighbours dots,
     * and the dots at a window edge with more picks beyond it shrink, hinting the list goes on.
     */
    private void updateDots() {
        if (!hero) return;
        int n = showing.size();
        int shown = Math.min(DOTS, n);
        int first = Math.max(0, Math.min(index - shown / 2, n - shown));
        if (dots.getChildCount() != shown) {
            dots.removeAllViews();
            for (int i = 0; i < shown; i++) {
                View d = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(3));
                d.setBackground(g);
                dots.addView(d, linear(6, 6, i == 0 ? 0 : 6, 0, 0, 0));
            }
        }
        for (int i = 0; i < shown; i++) {
            int at = first + i;
            boolean edge = (i == 0 && first > 0) || (i == shown - 1 && at < n - 1);
            View d = dots.getChildAt(i);
            ((GradientDrawable) d.getBackground()).setColor(at == index ? 0xE6FFFFFF : 0x59FFFFFF);
            int size = edge ? 4 : 6;
            d.getLayoutParams().width = dp(at == index ? 18 : size);
            d.getLayoutParams().height = dp(at == index ? 6 : size);
            d.requestLayout();
        }
    }

    /** "Not interested": the pick goes away on this TV for good (Settings can bring hidden titles back). */
    private void hide(Pick p) {
        prefs.hide(p);
        Toast.makeText(this, "Hidden: " + p.title, Toast.LENGTH_SHORT).show();
        picks.remove(p);
        List<Pick> group = byService.get(p.service);
        if (group != null) group.remove(p);
        if (picks.isEmpty()) {
            lastTile.requestFocus();
            return;
        }
        index = Math.min(index, picks.size() - 1);
        current = null;
        show(picks.get(index));
    }

    // ---- Backdrop ----------------------------------------------------------------------------------

    private ImageView backdropLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    private void crossfade(Bitmap art) {
        ImageView old = backFront;
        backFront = backRear;
        backRear = old;
        backFront.animate().cancel();
        backFront.setImageBitmap(art);
        backFront.setAlpha(0f);
        // The incoming layer goes just above the outgoing one, under the UI.
        root.removeView(backFront);
        root.addView(backFront, root.indexOfChild(backRear) + 1);
        backFront.animate().alpha(1f).setDuration(700).setInterpolator(ease).withEndAction(() -> {
            if (backRear.getAlpha() > 0f) backRear.setAlpha(0f);
        }).start();
    }

    // ---- Theme -------------------------------------------------------------------------------------

    /** Every minute: the look (Auto follows the sunset) and the audience (follows the evening schedule). */
    private void themeCheck() {
        long now = System.currentTimeMillis();
        Prefs.Theme theme = prefs.theme();
        boolean isDay = theme == Prefs.Theme.AUTO ? Sun.isDay(Config.LATITUDE, Config.LONGITUDE, now)
                : theme == Prefs.Theme.LIGHT;
        if (day == null || day != isDay) applyTheme(isDay);
        Audience scheduled = prefs.audienceAt(now);
        Season inSeason = prefs.seasonal() ? Season.at(now) : null;
        boolean fullDay = inSeason != null && inSeason.isFullDay(now);
        if (scheduled != audience || inSeason != season || fullDay != holidayDay) {
            audience = scheduled;
            season = inSeason;
            holidayDay = fullDay;
            loadPicks(true);
        }
        paintGlass(glassButtons.indexOf(wifiButton));
        handler.removeCallbacks(themeCheck);
        handler.postDelayed(themeCheck, THEME_CHECK_MS);
    }

    /** Light glass by day, dark glass after sunset. Backdrops keep their dark shade so text stays readable. */
    private void applyTheme(boolean isDay) {
        day = isDay;
        int glass = isDay ? 0xD9F2F2F5 : 0x33FFFFFF;
        int stroke = isDay ? 0x66FFFFFF : 0x22FFFFFF;
        ((GradientDrawable) dock.getBackground()).setColor(glass);
        ((GradientDrawable) dock.getBackground()).setStroke(dp(1), stroke);
        for (int i = 0; i < glassButtons.size(); i++) paintGlass(i);
    }

    private void paintGlass(int i) {
        View b = glassButtons.get(i);
        boolean focused = b.isFocused();
        boolean isDay = day != null && day;
        ((GradientDrawable) b.getBackground()).setColor(focused ? Color.WHITE : isDay ? 0xD9F2F2F5 : 0x33FFFFFF);
        glassGlyphs.get(i).setColor(focused || isDay ? 0xFF1C1C1E : Color.WHITE);
        if (b == wifiButton) {
            int level = wifiLevel();
            glassGlyphs.get(i).setBars(level);
            if (level == GlyphDrawable.OFFLINE && !focused) glassGlyphs.get(i).setColor(0xFFF87171);
        }
    }

    /** Signal bars (0-3) on a working connection, or {@link GlyphDrawable#OFFLINE}. */
    @SuppressWarnings("deprecation")
    private int wifiLevel() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return GlyphDrawable.OFFLINE;
        }
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 3;
        int rssi = getSystemService(WifiManager.class).getConnectionInfo().getRssi();
        return WifiManager.calculateSignalLevel(rssi, 4);
    }

    /** Repaints the Wi-Fi icon as soon as the connection changes, not just on the minute check. */
    private final ConnectivityManager.NetworkCallback networkWatch = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            handler.post(() -> paintGlass(glassButtons.indexOf(wifiButton)));
        }

        @Override
        public void onLost(Network network) {
            handler.post(() -> paintGlass(glassButtons.indexOf(wifiButton)));
        }
    };

    // ---- Views -------------------------------------------------------------------------------------

    private View buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 56, THIN, Color.WHITE);
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(12), 0, 0, 0x40000000);
        box.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        style(date, 16, REGULAR, 0xCCFFFFFF);
        date.setLetterSpacing(0.03f);
        date.setShadowLayer(dp(8), 0, 0, 0x40000000);
        box.addView(date, linear(-2, -2, 4, 4, 0, 0));
        return box;
    }

    private View buildButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        wifiButton = glassButton(GlyphDrawable.Kind.WIFI, new Intent(Settings.ACTION_WIFI_SETTINGS));
        row.addView(wifiButton, linear(44, 44, 0, 0, 0, 0));
        row.addView(glassButton(GlyphDrawable.Kind.GEAR, new Intent(this, SettingsActivity.class)),
                linear(44, 44, 12, 0, 0, 0));
        return row;
    }

    private View glassButton(GlyphDrawable.Kind kind, Intent target) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        b.setBackground(bg);
        GlyphDrawable glyph = new GlyphDrawable(kind);
        glyph.setColor(Color.WHITE);
        View icon = new View(this);
        icon.setBackground(glyph);
        b.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        glassButtons.add(b);
        glassGlyphs.add(glyph);
        int i = glassButtons.size() - 1;
        b.setOnFocusChangeListener((v, focused) -> {
            paintGlass(i);
            v.animate().scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f)
                    .setInterpolator(ease).setDuration(180).start();
        });
        b.setOnClickListener(v -> startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return b;
    }

    private View buildInfo() {
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        info.setAlpha(0f);

        titleBox = new FrameLayout(this);
        titleBox.setPivotX(0);
        titleBox.setScaleX(0.7f);
        titleBox.setScaleY(0.7f);
        logoView = new ImageView(this);
        logoView.setAdjustViewBounds(true);
        logoView.setMaxWidth(dp(400));
        logoView.setMaxHeight(dp(110));
        titleBox.addView(logoView, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        titleText = text("", 44, MEDIUM, Color.WHITE);
        titleText.setMaxLines(2);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        titleText.setShadowLayer(dp(10), 0, 0, 0x66000000);
        titleBox.addView(titleText, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));
        info.addView(titleBox, linear(-1, 110, 0, 0, 0, 0));
        // Scale from the bottom-left corner, so the title grows up and to the right.
        titleBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setPivotY(b - t));

        metaText = text("", 15, MEDIUM, 0xE6FFFFFF);
        info.addView(metaText, linear(-1, -2, 0, 10, 0, 0));

        overviewText = text("", 15, REGULAR, 0xBFFFFFFF);
        overviewText.setMaxLines(3);
        overviewText.setEllipsize(TextUtils.TruncateAt.END);
        overviewText.setLineSpacing(0, 1.15f);
        overviewText.setVisibility(View.GONE);
        info.addView(overviewText, linear(-1, -2, 0, 10, 0, 0));

        play = new LinearLayout(this);
        play.setOrientation(LinearLayout.HORIZONTAL);
        play.setGravity(Gravity.CENTER_VERTICAL);
        play.setPadding(dp(18), dp(8), dp(18), dp(8));
        play.setId(View.generateViewId());
        // Grow from the left edge when focused: centred, it would poke past the info block and get clipped
        // while the block fades between picks.
        play.setPivotX(0);
        play.setFocusable(false);
        play.setAlpha(0f);
        // Discreet frosted glass: the title is the star, the button just says where OK goes.
        GradientDrawable playBg = new GradientDrawable();
        playBg.setCornerRadius(dp(20));
        playBg.setColor(0x12FFFFFF);
        play.setBackground(playBg);
        playLabel = text("", 15, MEDIUM, 0xD9FFFFFF);
        play.addView(playLabel);
        play.setOnFocusChangeListener((v, focused) -> {
            playBg.setColor(focused ? 0x1FFFFFFF : 0x12FFFFFF);
            if (focused && !hero) enterHero();
            if (!focused && hero) exitHero();
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                    .setInterpolator(ease).setDuration(180).start();
        });
        play.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: step(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: step(1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: lastTile.requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: wifiButton.requestFocus(); return true;
                default: return false;
            }
        });
        play.setOnClickListener(v -> {
            if (current != null) open(current);
        });
        play.setOnLongClickListener(v -> {
            if (current != null) hide(current);
            return true;
        });
        LinearLayout playRow = new LinearLayout(this);
        playRow.setClipChildren(false);
        playRow.addView(play, new LinearLayout.LayoutParams(-2, -2));
        info.addView(playRow, linear(-2, -2, 0, 18, 0, 0));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setVisibility(View.GONE);
        info.addView(dots, linear(-2, 8, 2, 18, 0, 0));
        infoSlot = new FrameLayout(this);
        infoSlot.setClipChildren(false);
        infoSlot.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return infoSlot;
    }

    private View buildDock() {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(30), dp(22), dp(30), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0x33FFFFFF);
        dock.setBackground(bg);
        PackageManager pm = getPackageManager();
        for (String pkg : dockApps) {
            Intent launch = launchIntent(pkg);
            if (launch == null) continue;
            dock.addView(buildTile(pm, pkg, launch),
                    linear(128, 72, dock.getChildCount() == 0 ? 0 : 20, 0, 0, 0));
        }
        for (View tile : tiles) tile.setNextFocusUpId(play.getId());
        return dock;
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        return launch != null ? launch : pm.getLaunchIntentForPackage(pkg);
    }

    /** The app's TV banner (YouTube's look for SmartTube), or null when it ships none. */
    private Drawable bannerOf(String pkg) {
        if (pkg.equals(SMARTTUBE)) return new YouTubeBanner();
        try {
            Drawable art = getPackageManager().getApplicationBanner(pkg);
            return art != null ? art : getPackageManager().getActivityBanner(launchIntent(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private Drawable artFor(String pkg) {
        Drawable banner = bannerOf(pkg);
        if (banner != null) return banner;
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private View buildTile(PackageManager pm, String pkg, Intent launch) {
        boolean banner = bannerOf(pkg) != null;
        Drawable art = artFor(pkg);

        FrameLayout tile = new FrameLayout(this);
        tile.setTag(pkg);
        tile.setFocusable(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        tile.setClipToOutline(true);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0xFF1C1C22);
        tile.setBackground(plate);

        ImageView image = new ImageView(this);
        image.setImageDrawable(art);
        if (banner) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        }

        // The glossy highlight Apple TV puts on the focused icon.
        View shine = new View(this);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0x47FFFFFF, 0x0DFFFFFF, 0x00FFFFFF}));
        shine.setAlpha(0f);
        tile.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        glowOf.put(tile, Shade.apply(ambientOf(art)));
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.18f : 1f).scaleY(focused ? 1.18f : 1f)
                    .translationY(focused ? -dp(4) : 0).translationZ(focused ? dp(20) : 0)
                    .setInterpolator(ease).setDuration(220).start();
            shine.animate().alpha(focused ? 1f : 0f).setDuration(220).start();
            if (focused) onTileFocused(v);
        });
        tile.setOnClickListener(v -> startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        tiles.add(tile);
        return tile;
    }

    /** An ambient glow in the artwork's two most vivid colours, on black: the backdrop for apps without picks. */
    private static Bitmap ambientOf(Drawable d) {
        Bitmap sample = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState().newDrawable().mutate();
        copy.setBounds(0, 0, 16, 9);
        copy.draw(new Canvas(sample));
        int main = vivid(sample, -1);
        int second = vivid(sample, main);
        sample.recycle();

        int w = 320;
        int h = 180;
        Bitmap glow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(glow);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(w * 0.72f, h * 0.18f, w * 0.75f,
                new int[] {alpha(main, 235), alpha(main, 90), 0}, new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.12f, h * 0.85f, w * 0.6f,
                new int[] {alpha(second, 170), alpha(second, 50), 0}, new float[] {0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return glow;
    }

    /** The most saturated, bright pixel whose hue is far from {@code avoid} (-1: no constraint). */
    private static int vivid(Bitmap b, int avoid) {
        float[] hsv = new float[3];
        float[] avoidHsv = new float[3];
        if (avoid != -1) Color.colorToHSV(avoid, avoidHsv);
        int best = -1;
        float bestScore = 0.15f;
        for (int x = 0; x < b.getWidth(); x++) {
            for (int y = 0; y < b.getHeight(); y++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                Color.colorToHSV(px, hsv);
                if (avoid != -1) {
                    float dh = Math.abs(hsv[0] - avoidHsv[0]);
                    if (Math.min(dh, 360 - dh) < 35) continue;
                }
                float score = hsv[1] * hsv[2];
                if (score > bestScore) {
                    bestScore = score;
                    best = px;
                }
            }
        }
        if (best != -1) return best;
        if (avoid == -1) return 0xFF5B5BD6;
        // Nothing else vivid in the artwork: a neighbouring hue of the main colour.
        avoidHsv[0] = (avoidHsv[0] + 40f) % 360f;
        return Color.HSVToColor(avoidHsv);
    }

    private static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        style(t, sp, face, color);
        return t;
    }

    private static void style(TextView t, int sp, Typeface face, int color) {
        t.setTextSize(sp);
        t.setTypeface(face);
        t.setTextColor(color);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams frame(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size(w), size(h), gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams linear(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size(w), size(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int size(int v) {
        return v < 0 ? v : dp(v);
    }
}
