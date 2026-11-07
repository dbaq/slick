package tv.slick;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Picks plus their artwork, kept on disk so the home screen never waits on the network:
 * the cached list shows at once, a fresh one replaces it at most once an hour.
 */
final class PickStore {

    interface Listener {
        void onPicks(List<Pick> picks);
    }

    interface ImageCallback {
        void onImage(Bitmap bitmap);
    }

    private static final String TAG = "Slick";
    private static final long MAX_AGE_MS = 60 * 60 * 1000;
    private static final String IMAGES = "https://image.tmdb.org/t/p/";

    private final File filesDir;
    private final File imageDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getAllocationByteCount();
        }
    };

    PickStore(Context context) {
        filesDir = context.getFilesDir();
        imageDir = new File(context.getCacheDir(), "tmdb");
        imageDir.mkdirs();
    }

    /** Delivers the cached picks (when asked to), then fresh ones when the cache is older than an hour. */
    void load(Audience audience, Season season, boolean holidayDay, Set<String> hidden, Listener listener,
            boolean deliverCached) {
        String when = season == null ? "" : "-" + season.name() + (holidayDay ? "-day" : "");
        File listFile = new File(filesDir, ("picks-v7-" + audience.name() + when + ".json").toLowerCase());
        network.execute(() -> {
            List<Pick> cached = readList(listFile);
            if (deliverCached && !cached.isEmpty()) main.post(() -> listener.onPicks(cached));
            if (!cached.isEmpty() && System.currentTimeMillis() - listFile.lastModified() < MAX_AGE_MS) return;
            try {
                List<Pick> fresh = Tmdb.fetchPicks(audience, season, holidayDay, hidden, new Watchmode(filesDir));
                for (Pick p : fresh) {
                    fetchToDisk(backdropUrl(p));
                    if (!p.logo.isEmpty()) fetchToDisk(logoUrl(p));
                }
                writeList(listFile, fresh);
                for (Pick p : fresh) {
                    Log.i(TAG, "pick " + audience + " | " + p.service.label + " | " + (p.series ? "show " : "movie")
                            + " | " + p.title + " | " + p.meta);
                }
                main.post(() -> listener.onPicks(fresh));
            } catch (IOException | JSONException e) {
                // Offline or TMDB down: keep showing what we have and try again next time.
                Log.w(TAG, "picks refresh failed", e);
            }
        });
    }

    /** The backdrop with {@link Shade} already applied. */
    void backdrop(Pick p, ImageCallback cb) {
        image(backdropUrl(p), true, cb);
    }

    void logo(Pick p, ImageCallback cb) {
        image(logoUrl(p), false, cb);
    }

    /** A small backdrop for search's tiles: a quarter of the pixels, no shade. */
    void thumb(Pick p, ImageCallback cb) {
        image(IMAGES + "w780" + p.backdrop, false, cb);
    }

    private static String backdropUrl(Pick p) {
        return IMAGES + "w1280" + p.backdrop;
    }

    private static String logoUrl(Pick p) {
        return IMAGES + "w500" + p.logo;
    }

    private void image(String url, boolean shaded, ImageCallback cb) {
        Bitmap hit = memory.get(url);
        if (hit != null) {
            cb.onImage(hit);
            return;
        }
        decoder.execute(() -> {
            File f = fileFor(url);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = url.contains("/w780/") ? 2 : 1;
            Bitmap decoded = f.exists() ? BitmapFactory.decodeFile(f.getPath(), options) : null;
            if (decoded == null) {
                network.execute(() -> {
                    try {
                        fetchToDisk(url);
                        image(url, shaded, cb);
                    } catch (IOException e) {
                        Log.w(TAG, "image failed " + url, e);
                    }
                });
                return;
            }
            Bitmap b = shaded ? Shade.apply(decoded) : decoded;
            if (b != decoded) decoded.recycle();
            b.prepareToDraw();
            memory.put(url, b);
            main.post(() -> cb.onImage(b));
        });
    }

    private void fetchToDisk(String url) throws IOException {
        File f = fileFor(url);
        if (f.exists()) return;
        byte[] bytes = Tmdb.download(url, false);
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + f);
    }

    private File fileFor(String url) {
        return new File(imageDir, url.substring(IMAGES.length()).replace('/', '_'));
    }

    private static List<Pick> readList(File listFile) {
        if (!listFile.exists()) return Collections.emptyList();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(listFile.toPath()), "UTF-8"));
            List<Pick> picks = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) picks.add(Pick.fromJson(a.getJSONObject(i)));
            return picks;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cached picks unreadable", e);
            return Collections.emptyList();
        }
    }

    private static void writeList(File listFile, List<Pick> picks) throws IOException, JSONException {
        JSONArray a = new JSONArray();
        for (Pick p : picks) a.put(p.toJson());
        File tmp = new File(listFile.getPath() + ".part");
        Files.write(tmp.toPath(), a.toString().getBytes("UTF-8"));
        if (!tmp.renameTo(listFile)) throw new IOException("rename " + listFile);
    }
}
