package tv.slick;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The title's own web address in its streaming app (Netflix, Disney+ and Prime Video open those on the
 * title's page), from Watchmode. Two requests per title, the first time only: answers, including
 * "none", are kept on disk.
 */
final class Watchmode {

    private static final String API = "https://api.watchmode.com/v1";

    private final File cacheFile;
    private final JSONObject cache;

    Watchmode(File cacheFile) throws IOException, JSONException {
        this.cacheFile = cacheFile;
        cache = cacheFile.exists()
                ? new JSONObject(new String(Files.readAllBytes(cacheFile.toPath()), "UTF-8"))
                : new JSONObject();
    }

    /** "" when Watchmode doesn't know the title on that service: the pick then opens the app. */
    String link(Pick p) throws IOException, JSONException {
        String key = (p.series ? "tv:" : "movie:") + p.tmdbId + ":" + p.service.name();
        if (cache.has(key)) return cache.getString(key);
        String link = lookup(p);
        cache.put(key, link);
        Files.write(cacheFile.toPath(), cache.toString().getBytes("UTF-8"));
        return link;
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

    private static JSONObject get(String path) throws IOException, JSONException {
        return new JSONObject(new String(Tmdb.download(API + path + "&apiKey=" + Config.WATCHMODE_KEY, false), "UTF-8"));
    }
}
