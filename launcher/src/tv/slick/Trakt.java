package tv.slick;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * This TV's Trakt account: signed in once from Settings with a code typed at trakt.tv/activate, then read
 * only. Each TV keeps and renews its own token (files/trakt.json). The Trakt app has no client secret
 * (it's a PKCE app), and neither the code sign-in nor renewing needs one. Network calls block: call them
 * off the main thread.
 */
final class Trakt {

    private static final String API = "https://api.trakt.tv";
    private static final String FILE = "trakt.json";
    /** Renew when the token (good for 7 days) has less than this left. */
    private static final long RENEW_BEFORE_S = 2 * 24 * 3600;
    /** Shows checked for an unwatched next episode, most recently watched first. */
    private static final int CONTINUE_SHOWS = 12;
    private static final long DAY_MS = 24L * 3600 * 1000;
    /** New seasons are announced this long before, and stay "new" this long after. */
    static final int SOON_DAYS = 28;
    static final int NEW_DAYS = 14;

    /** What a sign-in code looks like on screen, and what to poll with. */
    static final class Code {
        final String userCode;
        final String url;
        final String deviceCode;
        final int intervalS;
        final long expiresAtMs;

        Code(JSONObject o) throws JSONException {
            userCode = o.getString("user_code");
            url = o.getString("verification_url");
            deviceCode = o.getString("device_code");
            intervalS = o.getInt("interval");
            expiresAtMs = System.currentTimeMillis() + o.getInt("expires_in") * 1000L;
        }
    }

    /** The next unwatched episode of a show in progress. */
    static final class Next {
        final int tmdbId;
        final int season;
        final int episode;

        Next(int tmdbId, int season, int episode) {
            this.tmdbId = tmdbId;
            this.season = season;
            this.episode = episode;
        }
    }

    /** A new season of a show in the history: coming (daysAway > 0) or out (daysAway <= 0). */
    static final class Premiere {
        final int tmdbId;
        final int season;
        final long airsMs;

        Premiere(int tmdbId, int season, long airsMs) {
            this.tmdbId = tmdbId;
            this.season = season;
            this.airsMs = airsMs;
        }
    }

    /** Everything the picks need from the account. Candidates are "movie:123" / "tv:456". */
    static final class Taste {
        final Set<String> watched;
        final List<Next> next;
        final List<Premiere> premieres;
        final List<String> watchlist;
        final List<String> recommended;
        /** Shows by episodes watched, most first (TMDB ids): the reality ones seed "more like these". */
        final List<Integer> mostWatched;

        Taste(Set<String> watched, List<Next> next, List<Premiere> premieres, List<String> watchlist,
                List<String> recommended, List<Integer> mostWatched) {
            this.watched = watched;
            this.next = next;
            this.premieres = premieres;
            this.watchlist = watchlist;
            this.recommended = recommended;
            this.mostWatched = mostWatched;
        }
    }

    private final File file;

    Trakt(File filesDir) {
        file = new File(filesDir, FILE);
    }

    boolean connected() {
        return file.exists();
    }

    void disconnect() {
        if (!file.delete() && file.exists()) throw new IllegalStateException("couldn't delete " + file);
    }

    // ---- Sign-in ----------------------------------------------------------------------------------

    Code start() throws IOException, JSONException {
        return new Code(new JSONObject(post("/oauth/device/code", new JSONObject().put("client_id", Config.TRAKT_CLIENT_ID), null)));
    }

    /** True once the code was approved (the token is then saved); false while it's still waiting. */
    boolean poll(Code code) throws IOException, JSONException {
        HttpURLConnection c = open("/oauth/device/token", null);
        send(c, new JSONObject().put("code", code.deviceCode).put("client_id", Config.TRAKT_CLIENT_ID));
        try {
            int status = c.getResponseCode();
            if (status == 400 || status == 429) return false; // not approved yet / polling too fast
            if (status != 200) throw new IOException("Trakt sign-in -> HTTP " + status);
            save(new JSONObject(read(c)));
            return true;
        } finally {
            c.disconnect();
        }
    }

    // ---- Token ------------------------------------------------------------------------------------

    private String accessToken() throws IOException, JSONException {
        JSONObject t = new JSONObject(new String(Files.readAllBytes(file.toPath()), "UTF-8"));
        long expires = t.getLong("created_at") + t.getLong("expires_in");
        if (expires - System.currentTimeMillis() / 1000 > RENEW_BEFORE_S) return t.getString("access_token");
        JSONObject renewed = new JSONObject(post("/oauth/token", new JSONObject()
                .put("refresh_token", t.getString("refresh_token"))
                .put("client_id", Config.TRAKT_CLIENT_ID)
                .put("grant_type", "refresh_token"), null));
        save(renewed);
        return renewed.getString("access_token");
    }

    private void save(JSONObject token) throws IOException {
        File tmp = new File(file.getPath() + ".part");
        Files.write(tmp.toPath(), token.toString().getBytes("UTF-8"));
        if (!tmp.renameTo(file)) throw new IOException("rename " + file);
    }

    // ---- What the picks need ----------------------------------------------------------------------

    Taste taste() throws IOException, JSONException {
        String token = accessToken();
        JSONArray shows = all("/sync/watched/shows?extended=noseasons", token);
        JSONArray movies = all("/sync/watched/movies", token);

        Set<String> watched = new HashSet<>();
        List<JSONObject> byRecent = new ArrayList<>();
        List<JSONObject> byPlays = new ArrayList<>();
        for (int i = 0; i < shows.length(); i++) {
            JSONObject s = shows.getJSONObject(i);
            int tmdb = tmdbId(s.getJSONObject("show"));
            if (tmdb > 0) watched.add("tv:" + tmdb);
            byRecent.add(s);
            byPlays.add(s);
        }
        for (int i = 0; i < movies.length(); i++) {
            int tmdb = tmdbId(movies.getJSONObject(i).getJSONObject("movie"));
            if (tmdb > 0) watched.add("movie:" + tmdb);
        }

        Collections.sort(byRecent, (a, b) -> b.optString("last_watched_at").compareTo(a.optString("last_watched_at")));
        List<Next> next = new ArrayList<>();
        for (JSONObject s : byRecent.subList(0, Math.min(CONTINUE_SHOWS, byRecent.size()))) {
            JSONObject show = s.getJSONObject("show");
            int tmdb = tmdbId(show);
            if (tmdb <= 0) continue;
            int[] ne = nextAfterLatest(show, token);
            if (ne != null) next.add(new Next(tmdb, ne[0], ne[1]));
        }

        // Premieres from NEW_DAYS ago to SOON_DAYS ahead; Trakt's calendar takes at most 33 days at a time.
        List<Premiere> premieres = new ArrayList<>();
        long now = System.currentTimeMillis();
        long from = now - NEW_DAYS * DAY_MS;
        int total = NEW_DAYS + SOON_DAYS + 1;
        for (int done = 0; done < total; done += 33) {
            int days = Math.min(33, total - done);
            JSONArray cal = new JSONArray(get("/calendars/my/shows/premieres/" + date(from + done * DAY_MS) + "/" + days, token));
            for (int i = 0; i < cal.length(); i++) {
                JSONObject e = cal.getJSONObject(i);
                int tmdb = tmdbId(e.getJSONObject("show"));
                if (tmdb <= 0) continue;
                premieres.add(new Premiere(tmdb, e.getJSONObject("episode").getInt("season"), parse(e.getString("first_aired"))));
            }
        }

        List<String> watchlist = new ArrayList<>();
        JSONArray wl = new JSONArray(get("/sync/watchlist", token));
        for (int i = 0; i < wl.length(); i++) {
            JSONObject w = wl.getJSONObject(i);
            String type = w.getString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            JSONObject item = w.getJSONObject(type);
            int tmdb = tmdbId(item);
            if (tmdb <= 0) continue;
            String key = (type.equals("show") ? "tv:" : "movie:") + tmdb;
            if (!watched.contains(key)) {
                watchlist.add(key);
                continue;
            }
            // Seen already: a movie is done; a show only comes back when episodes are left, as its next one.
            if (type.equals("movie")) continue;
            boolean listed = false;
            for (Next n : next) listed |= n.tmdbId == tmdb;
            int[] ne = listed ? null : nextAfterLatest(item, token);
            if (ne != null) next.add(new Next(tmdb, ne[0], ne[1]));
        }

        List<String> recommended = new ArrayList<>();
        JSONArray recShows = new JSONArray(get("/recommendations/shows?limit=30&ignore_collected=true", token));
        JSONArray recMovies = new JSONArray(get("/recommendations/movies?limit=30&ignore_collected=true", token));
        for (int i = 0; i < Math.max(recShows.length(), recMovies.length()); i++) {
            if (i < recShows.length() && tmdbId(recShows.getJSONObject(i)) > 0) recommended.add("tv:" + tmdbId(recShows.getJSONObject(i)));
            if (i < recMovies.length() && tmdbId(recMovies.getJSONObject(i)) > 0) recommended.add("movie:" + tmdbId(recMovies.getJSONObject(i)));
        }

        Collections.sort(byPlays, (a, b) -> b.optInt("plays") - a.optInt("plays"));
        List<Integer> mostWatched = new ArrayList<>();
        for (JSONObject s : byPlays) {
            int tmdb = tmdbId(s.getJSONObject("show"));
            if (tmdb > 0) mostWatched.add(tmdb);
        }
        return new Taste(watched, next, premieres, watchlist, recommended, mostWatched);
    }

    /**
     * The aired episode right after the latest one watched, as {season, episode}; null when caught up.
     * Not Trakt's own "next episode", which is the first unwatched one: an old episode that never got
     * logged would otherwise send you back to it, when watching later ones means it was seen.
     */
    private int[] nextAfterLatest(JSONObject show, String token) throws IOException, JSONException {
        JSONObject p = new JSONObject(get("/shows/" + show.getJSONObject("ids").getInt("trakt")
                + "/progress/watched?hidden=false&specials=false", token));
        List<int[]> aired = new ArrayList<>();
        int latest = -1;
        JSONArray seasons = p.getJSONArray("seasons");
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.getJSONObject(i);
            if (season.getInt("number") == 0) continue;
            JSONArray episodes = season.getJSONArray("episodes");
            for (int j = 0; j < episodes.length(); j++) {
                JSONObject e = episodes.getJSONObject(j);
                if (e.getBoolean("completed")) latest = aired.size();
                aired.add(new int[] {season.getInt("number"), e.getInt("number")});
            }
        }
        return latest + 1 < aired.size() ? aired.get(latest + 1) : null;
    }

    private static int tmdbId(JSONObject item) throws JSONException {
        JSONObject ids = item.getJSONObject("ids");
        return ids.isNull("tmdb") ? 0 : ids.getInt("tmdb");
    }

    /** Every page of a paginated list (the history runs to hundreds of titles). */
    private JSONArray all(String path, String token) throws IOException, JSONException {
        JSONArray out = new JSONArray();
        for (int page = 1, pages = 1; page <= pages; page++) {
            HttpURLConnection c = open(path + (path.contains("?") ? "&" : "?") + "page=" + page + "&limit=250", token);
            try {
                if (c.getResponseCode() != 200) throw new IOException(path + " -> HTTP " + c.getResponseCode());
                String count = c.getHeaderField("X-Pagination-Page-Count");
                pages = count == null ? 1 : Integer.parseInt(count);
                JSONArray a = new JSONArray(read(c));
                for (int i = 0; i < a.length(); i++) out.put(a.get(i));
            } finally {
                c.disconnect();
            }
        }
        return out;
    }

    // ---- HTTP -------------------------------------------------------------------------------------

    private String get(String path, String token) throws IOException {
        HttpURLConnection c = open(path, token);
        try {
            if (c.getResponseCode() != 200) throw new IOException(path + " -> HTTP " + c.getResponseCode());
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private String post(String path, JSONObject body, String token) throws IOException {
        HttpURLConnection c = open(path, token);
        send(c, body);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException(path + " -> HTTP " + c.getResponseCode());
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", Config.TRAKT_CLIENT_ID);
        c.setRequestProperty("User-Agent", "Slick/1.0");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static void send(HttpURLConnection c, JSONObject body) throws IOException {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes("UTF-8"));
        }
    }

    private static String read(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private static String date(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /** Trakt's "2026-11-20T02:00:00.000Z". */
    private static long parse(String iso) throws IOException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            throw new IOException("bad date " + iso, e);
        }
    }
}
