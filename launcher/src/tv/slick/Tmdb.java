package tv.slick;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds the picks for an audience from TMDB. Blocking: call off the main thread. */
final class Tmdb {

    static final int PER_SERVICE = 4;
    private static final String API = "https://api.themoviedb.org/3";
    private static final int TRENDING_PAGES = 3;
    /** TMDB's watch-provider ids (US) for the dock's services. */
    private static final int[] PROVIDERS = {8, 337, 9};
    private static final Pick.Service[] PROVIDER_SERVICE = {Pick.Service.NETFLIX, Pick.Service.DISNEY, Pick.Service.PRIME};
    /** Kids, animation and family shows: discover has no rating filter for TV, the details check it instead. */
    private static final String KID_SHOW_GENRES = "10762|16|10751";

    static List<Pick> fetchPicks(Audience audience, Watchmode links) throws IOException, JSONException {
        Map<Pick.Service, List<Pick>> groups = new EnumMap<>(Pick.Service.class);
        for (Pick.Service s : Pick.Service.values()) groups.put(s, new ArrayList<>());

        for (String candidate : candidates(audience)) {
            if (allFull(groups)) break;
            boolean series = candidate.startsWith("tv:");
            Pick pick = details(series, Integer.parseInt(candidate.substring(candidate.indexOf(':') + 1)), audience);
            if (pick == null) continue;
            List<Pick> group = groups.get(pick.service);
            if (group.size() < PER_SERVICE) group.add(pick);
        }

        List<Pick> picks = new ArrayList<>();
        for (List<Pick> group : groups.values()) {
            for (Pick p : group) picks.add(p.service == Pick.Service.STREMIO ? p : p.withLink(links.link(p)));
        }
        return picks;
    }

    /**
     * "movie:123" / "tv:456", most promising first. Adults get the week's trending titles; kids and
     * family get each service's most popular titles in their ratings, then trending for Stremio.
     */
    private static Set<String> candidates(Audience audience) throws IOException, JSONException {
        Set<String> out = new LinkedHashSet<>();
        if (audience.discoverCertification != null) {
            List<List<String>> perService = new ArrayList<>();
            for (int provider : PROVIDERS) {
                String where = "&watch_region=" + Config.REGION + "&with_watch_providers=" + provider
                        + "&sort_by=popularity.desc&language=en-US";
                List<String> ids = new ArrayList<>();
                List<String> movies = ids("movie", get("/discover/movie?certification_country=US&certification="
                        + audience.discoverCertification + where).getJSONArray("results"));
                List<String> shows = ids("tv", get("/discover/tv?with_genres=" + KID_SHOW_GENRES + where)
                        .getJSONArray("results"));
                for (int i = 0; i < Math.max(movies.size(), shows.size()); i++) {
                    if (i < movies.size()) ids.add(movies.get(i));
                    if (i < shows.size()) ids.add(shows.get(i));
                }
                perService.add(ids);
            }
            // Round-robin across services, so each fills before the list runs long.
            for (int i = 0; i < 40; i++) {
                for (List<String> ids : perService) if (i < ids.size()) out.add(ids.get(i));
            }
        }
        for (int page = 1; page <= TRENDING_PAGES; page++) {
            JSONArray results = get("/trending/all/week?language=en-US&page=" + page).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                if (type.equals("movie") || type.equals("tv")) out.add(type + ":" + r.getInt("id"));
            }
        }
        return out;
    }

    private static List<String> ids(String type, JSONArray results) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) out.add(type + ":" + results.getJSONObject(i).getInt("id"));
        return out;
    }

    private static boolean allFull(Map<Pick.Service, List<Pick>> groups) {
        for (List<Pick> g : groups.values()) if (g.size() < PER_SERVICE) return false;
        return true;
    }

    /**
     * Null when the title is skipped: not for this audience, no backdrop, or nowhere to open it
     * (no dock service and no IMDb id for Stremio).
     */
    private static Pick details(boolean series, int id, Audience audience) throws IOException, JSONException {
        JSONObject d = get("/" + (series ? "tv" : "movie") + "/" + id
                + "?language=en-US&append_to_response=watch/providers,external_ids,images,"
                + (series ? "content_ratings" : "release_dates") + "&include_image_language=en,null");
        if (d.isNull("backdrop_path")) return null;
        if (!audience.allows(series, rating(series, d), genreIds(d))) return null;

        String imdb = series ? d.getJSONObject("external_ids").optString("imdb_id") : d.optString("imdb_id");
        if (imdb.equals("null")) imdb = "";

        Pick.Service service = Pick.Service.STREMIO;
        String elsewhere = "";
        JSONObject region = d.getJSONObject("watch/providers").getJSONObject("results").optJSONObject(Config.REGION);
        if (region != null) {
            List<String> names = new ArrayList<>();
            for (String kind : new String[] {"flatrate", "ads", "free"}) {
                JSONArray list = region.optJSONArray(kind);
                for (int i = 0; list != null && i < list.length(); i++) {
                    names.add(list.getJSONObject(i).getString("provider_name"));
                }
            }
            service = serviceOf(names);
            if (service == Pick.Service.STREMIO && !names.isEmpty()) elsewhere = names.get(0);
        }
        if (service == Pick.Service.STREMIO && imdb.isEmpty()) return null;

        String title = d.optString(series ? "name" : "title");
        return new Pick(series, id, title, meta(series, d), d.optString("overview"),
                d.getString("backdrop_path"), logo(d), imdb, service, elsewhere, "");
    }

    /** The US rating, or "" when TMDB has none. */
    private static String rating(boolean series, JSONObject d) throws JSONException {
        if (series) {
            JSONArray results = d.getJSONObject("content_ratings").getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (r.getString("iso_3166_1").equals("US")) return r.optString("rating");
            }
            return "";
        }
        JSONArray countries = d.getJSONObject("release_dates").getJSONArray("results");
        for (int i = 0; i < countries.length(); i++) {
            JSONObject country = countries.getJSONObject(i);
            if (!country.getString("iso_3166_1").equals("US")) continue;
            JSONArray releases = country.getJSONArray("release_dates");
            for (int j = 0; j < releases.length(); j++) {
                String rating = releases.getJSONObject(j).optString("certification");
                if (!rating.isEmpty()) return rating;
            }
        }
        return "";
    }

    private static Set<Integer> genreIds(JSONObject d) throws JSONException {
        Set<Integer> out = new HashSet<>();
        JSONArray genres = d.getJSONArray("genres");
        for (int i = 0; i < genres.length(); i++) out.add(genres.getJSONObject(i).getInt("id"));
        return out;
    }

    private static Pick.Service serviceOf(List<String> providers) {
        for (Pick.Service s : PROVIDER_SERVICE) {
            for (String name : providers) {
                if (s == Pick.Service.NETFLIX && name.startsWith("Netflix")) return s;
                if (s == Pick.Service.DISNEY && name.startsWith("Disney Plus")) return s;
                if (s == Pick.Service.PRIME && name.startsWith("Amazon Prime Video")) return s;
            }
        }
        return Pick.Service.STREMIO;
    }

    private static String meta(boolean series, JSONObject d) throws JSONException {
        StringBuilder m = new StringBuilder(series ? "Series" : "Movie");
        JSONArray genres = d.getJSONArray("genres");
        for (int i = 0; i < Math.min(2, genres.length()); i++) {
            m.append(i == 0 ? " · " : ", ").append(genres.getJSONObject(i).getString("name"));
        }
        String date = d.optString(series ? "first_air_date" : "release_date");
        if (date.length() >= 4) m.append(" · ").append(date, 0, 4);
        if (series) {
            int seasons = d.optInt("number_of_seasons");
            if (seasons > 0) m.append(" · ").append(seasons).append(seasons == 1 ? " season" : " seasons");
        } else {
            int runtime = d.optInt("runtime");
            if (runtime > 0) m.append(" · ").append(runtime / 60).append("h ").append(runtime % 60).append("m");
        }
        return m.toString();
    }

    /** The English title logo, or "" (the title is then drawn as text). */
    private static String logo(JSONObject d) throws JSONException {
        JSONArray logos = d.getJSONObject("images").getJSONArray("logos");
        for (int i = 0; i < logos.length(); i++) {
            JSONObject l = logos.getJSONObject(i);
            if ("en".equals(l.optString("iso_639_1")) && l.getString("file_path").endsWith(".png")) {
                return l.getString("file_path");
            }
        }
        return "";
    }

    static byte[] download(String url, boolean authorized) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        if (authorized) c.setRequestProperty("Authorization", "Bearer " + Config.TMDB_TOKEN);
        try {
            if (c.getResponseCode() != 200) throw new IOException(url + " -> HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject get(String path) throws IOException, JSONException {
        return new JSONObject(new String(download(API + path, true), "UTF-8"));
    }
}
