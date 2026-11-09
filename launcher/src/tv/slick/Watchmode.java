package tv.slick;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Two things from Watchmode: the title's own web address in its streaming app (Netflix, Disney+ and Prime
 * Video open those on the title's page), and what arrived on each service lately. Answers are kept on disk:
 * links forever (two requests per title, the first time only), arrivals for the day. The free plan's
 * monthly quota is shared by every TV in the house.
 */
final class Watchmode {

    private static final String API = "https://api.watchmode.com/v1";
    /** Arrivals are read in windows of this many days: one window fits under the API's 250 rows. */
    private static final int WINDOW_DAYS = 30;
    private static final int WINDOWS = 2;
    private static final int RELEASES_PER_SERVICE = 30;
    private static final long DAY_MS = 24L * 3600 * 1000;

    private final File linksFile;
    private final File releasesFile;
    private final JSONObject links;
    /** The day's arrivals, newest first: {source, candidate} where candidate is "movie:id" or "tv:id". */
    private JSONArray releases;

    Watchmode(File filesDir) throws IOException, JSONException {
        linksFile = new File(filesDir, "links.json");
        releasesFile = new File(filesDir, "releases.json");
        links = linksFile.exists() ? new JSONObject(TextFile.read(linksFile)) : new JSONObject();
    }

    /** "" when Watchmode doesn't know the title on that service: the pick then opens the app. */
    String link(Pick p) throws IOException, JSONException {
        String key = (p.series ? "tv:" : "movie:") + p.tmdbId + ":" + p.service.name();
        if (links.has(key)) return links.getString(key);
        String link = lookup(p);
        links.put(key, link);
        TextFile.write(linksFile, links.toString());
        return link;
    }

    /**
     * Titles that arrived on {@code service} in the last two months, newest first, as "movie:id" / "tv:id"
     * (TMDB ids). For Stremio: what arrived on every service that isn't on the dock (HBO Max, Hulu…).
     */
    List<String> releases(Pick.Service service) throws IOException, JSONException {
        JSONArray all = todaysReleases();
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < all.length() && out.size() < RELEASES_PER_SERVICE; i++) {
            JSONObject r = all.getJSONObject(i);
            Pick.Service from = serviceNamed(r.getString("source"));
            if (from == service) out.add(r.getString("candidate"));
        }
        return new ArrayList<>(out);
    }

    private JSONArray todaysReleases() throws IOException, JSONException {
        if (releases != null) return releases;
        String today = day(System.currentTimeMillis());
        if (releasesFile.exists()) {
            JSONObject cached = new JSONObject(TextFile.read(releasesFile));
            if (cached.getString("day").equals(today)) return releases = cached.getJSONArray("rows");
        }
        JSONArray rows = new JSONArray();
        long end = System.currentTimeMillis();
        for (int w = 0; w < WINDOWS; w++, end -= WINDOW_DAYS * DAY_MS) {
            JSONArray page = get("/releases/?limit=250&start_date=" + day(end - (WINDOW_DAYS - 1) * DAY_MS)
                    + "&end_date=" + day(end)).getJSONArray("releases");
            // The API lists oldest first; arrivals should read newest first.
            for (int i = page.length() - 1; i >= 0; i--) {
                JSONObject r = page.getJSONObject(i);
                String type = r.optString("tmdb_type");
                int id = r.optInt("tmdb_id");
                if (id == 0 || !(type.equals("movie") || type.equals("tv"))) continue;
                rows.put(new JSONObject().put("source", r.getString("source_name")).put("candidate", type + ":" + id));
            }
        }
        TextFile.write(releasesFile, new JSONObject().put("day", today).put("rows", rows).toString());
        return releases = rows;
    }

    /** The dock service a Watchmode source stands for; every other source is Stremio's. */
    private static Pick.Service serviceNamed(String source) {
        for (Pick.Service s : Pick.Service.values()) {
            if (s != Pick.Service.STREMIO && s.label.equals(source)) return s;
        }
        return Pick.Service.STREMIO;
    }

    private static String lookup(Pick p) throws IOException, JSONException {
        JSONArray found = get("/search/?search_field=" + (p.series ? "tmdb_tv_id" : "tmdb_movie_id")
                + "&search_value=" + p.tmdbId).getJSONArray("title_results");
        if (found.length() == 0) return "";
        JSONArray sources = new JSONArray(new String(Tmdb.download(API + "/title/"
                + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + Config.REGION
                + "&apiKey=" + Config.WATCHMODE_KEY, false), "UTF-8"));
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.getJSONObject(i);
            if (s.getString("type").equals("sub") && s.getString("name").equals(p.service.label)) {
                return s.optString("web_url");
            }
        }
        return "";
    }

    private static String day(long ms) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms));
    }

    private static JSONObject get(String path) throws IOException, JSONException {
        return new JSONObject(new String(Tmdb.download(API + path + "&apiKey=" + Config.WATCHMODE_KEY, false), "UTF-8"));
    }
}
