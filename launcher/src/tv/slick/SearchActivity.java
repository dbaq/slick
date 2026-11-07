package tv.slick;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import tv.slick.SettingsActivity.Ui;

/** Search TMDB for any movie or show; titles for the current audience come first. OK opens it in its app. */
public class SearchActivity extends Activity {

    private static final String TAG = "Slick";
    private static final long TYPING_PAUSE_MS = 400;
    private static final int MIN_CHARS = 2;
    private static final int COLUMNS = 4;
    private static final int GAP_DP = 24;
    private static final int SIDE_DP = 120;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private PickStore store;
    private Audience audience;
    private EditText field;
    private TextView status;
    private LinearLayout grid;
    /** Bumped on every search: answers for an older query are dropped. */
    private int seq;
    private final Runnable searchNow = this::search;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new PickStore(this);
        audience = new Prefs(this).audienceAt(System.currentTimeMillis());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0B0B0F);
        page.setPadding(Ui.dp(this, SIDE_DP), Ui.dp(this, 48), Ui.dp(this, SIDE_DP), 0);
        page.setClipChildren(false);

        page.addView(buildField());
        status = Ui.text(this, audience.label + " titles first", 13, Ui.REGULAR, 0x80FFFFFF);
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        page.addView(status);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 48));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(page);
        field.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        network.shutdownNow();
    }

    private View buildField() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 16));
        bg.setColor(0x1FFFFFFF);
        box.setBackground(bg);

        GlyphDrawable glyph = new GlyphDrawable(GlyphDrawable.Kind.SEARCH);
        glyph.setColor(0x99FFFFFF);
        View icon = new View(this);
        icon.setBackground(glyph);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));

        field = new EditText(this);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setTextSize(22);
        field.setTypeface(Ui.REGULAR);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0x66FFFFFF);
        field.setHint("Search movies and shows");
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), 0, Ui.dp(this, 16));
        field.setOnFocusChangeListener((v, focused) -> bg.setColor(focused ? 0x33FFFFFF : 0x1FFFFFFF));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacks(searchNow);
                main.postDelayed(searchNow, TYPING_PAUSE_MS);
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            main.removeCallbacks(searchNow);
            search();
            return false; // let the keyboard close
        });
        box.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        return box;
    }

    private void search() {
        String query = field.getText().toString().trim();
        int mine = ++seq;
        if (query.length() < MIN_CHARS) {
            grid.removeAllViews();
            status.setText(audience.label + " titles first");
            return;
        }
        status.setText("Searching…");
        PackageManager pm = getPackageManager();
        network.execute(() -> {
            try {
                List<Pick> found = new ArrayList<>();
                for (Pick p : Tmdb.search(query, audience)) {
                    if (Titles.launchIntent(pm, p.service.pkg) != null) found.add(p);
                    else if (!p.imdbId.isEmpty()) found.add(p.inStremio());
                }
                main.post(() -> {
                    if (mine == seq) show(query, found);
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "search failed", e);
                main.post(() -> {
                    if (mine == seq) status.setText("Can't reach TMDB");
                });
            }
        });
    }

    private void show(String query, List<Pick> found) {
        grid.removeAllViews();
        status.setText(found.isEmpty() ? "Nothing found for “" + query + "”" : audience.label + " titles first");
        int width = (getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, SIDE_DP)
                - (COLUMNS - 1) * Ui.dp(this, GAP_DP)) / COLUMNS;
        LinearLayout row = null;
        for (int i = 0; i < found.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = Ui.dp(this, GAP_DP);
                grid.addView(row, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
            if (i % COLUMNS > 0) lp.leftMargin = Ui.dp(this, GAP_DP);
            row.addView(tile(found.get(i), width), lp);
        }
    }

    private View tile(Pick p, int width) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);

        FrameLayout art = new FrameLayout(this);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(Ui.dp(this, 12));
        artBg.setColor(0x14FFFFFF);
        art.setBackground(artBg);
        art.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        tile.addView(art, new LinearLayout.LayoutParams(width, width * 9 / 16));
        store.thumb(p, image::setImageBitmap);

        TextView title = Ui.text(this, p.title, 15, Ui.REGULAR, 0xCCFFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), 0, 0);
        tile.addView(title);
        TextView meta = Ui.text(this, year(p) + p.where(), 12, Ui.REGULAR, 0x80FFFFFF);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), 0, 0);
        tile.addView(meta);

        tile.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.06f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(160).start();
            title.setTextColor(focused ? Color.WHITE : 0xCCFFFFFF);
        });
        tile.setOnClickListener(v -> open(p));
        return tile;
    }

    /** "Series · 2019 · " from the pick's meta line ("Series · Animation, Comedy · 2019 · …"). */
    private static String year(Pick p) {
        String kind = p.series ? "Series" : "Movie";
        for (String part : p.meta.split(" · ")) {
            if (part.matches("\\d{4}")) return kind + " · " + part + " · ";
        }
        return kind + " · ";
    }

    private void open(Pick p) {
        PackageManager pm = getPackageManager();
        if (p.service == Pick.Service.STREMIO) {
            startActivity(Titles.intent(pm, p));
            return;
        }
        network.execute(() -> {
            String link;
            try {
                link = new Watchmode(getFilesDir()).link(p);
            } catch (IOException | JSONException e) {
                // Watchmode down: the app's home is still the right app.
                Log.w(TAG, "link failed", e);
                link = "";
            }
            Pick linked = p.withLink(link);
            main.post(() -> startActivity(Titles.intent(pm, linked)));
        });
    }
}
