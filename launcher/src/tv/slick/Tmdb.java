package tv.slick;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the picks for an audience from TMDB. Each service gets new titles (what just arrived on it, then
 * what's popular from the last months) and classics (best rated), English only, above a quality bar; in a
 * {@link Season}, half are holiday titles, and on the day itself all of them. Each service's list mixes
 * movies and shows and doesn't repeat one kind of story. Blocking: call off the main thread.
 */
final class Tmdb {

    static final int PER_SERVICE = 8;
    private static final int SEARCH_RESULTS = 16;
    private static final int SEARCH_THREADS = 6;
    private static final String API = "https://api.themoviedb.org/3";
    /** TMDB's watch-provider ids (US), in the same order as the services they stand for. */
    private static final int[] PROVIDERS = {8, 337, 9};
    private static final Pick.Service[] PROVIDER_SERVICE = {Pick.Service.NETFLIX, Pick.Service.DISNEY, Pick.Service.PRIME};
    /** Kids, animation and family shows: discover has no rating filter for TV, the details check it instead. */
    private static final String KID_SHOW_GENRES = "10762|16|10751";
    private static final String KID_MOVIE_GENRES = "16|10751";
    /** Classics start here: older films rarely land with today's families. */
    private static final String CLASSICS_SINCE = "1990-01-01";
    private static final int MIN_KID_SHOW_VOTES = 30;
    private static final long NEW_WINDOW_MS = 540L * 24 * 3600 * 1000;
    private static final long JUST_OUT_MS = 90L * 24 * 3600 * 1000;
    /** Quality bar: a title needs this rating from at least this many votes (fewer for what just came out). */
    private static final double MIN_RATING = 6.5;
    private static final int MIN_MOVIE_VOTES = 300;
    private static final int MIN_SHOW_VOTES = 100;
    private static final int MIN_JUST_OUT_VOTES = 25;
    /** Shorts (Pixar's Piper, Paperman) aren't what anyone means by a movie night. */
    private static final int MIN_RUNTIME = 40;
    /** Candidates kept per slot, so the final choice has room for variety. */
    private static final int POOL = 2;
    private static final int DISCOVER_PAGES = 2;
    /** At most this many picks per service share a {@link Candidate#flavor}. */
    private static final int MAX_PER_FLAVOR = 2;
    /** Genres nearly every kids' title has: they say nothing about what kind of story it is. */
    private static final Set<Integer> PLAIN_GENRES = new HashSet<>(java.util.Arrays.asList(16, 10751, 10762));
    /** Preschool shows still on the air count as new for Kids. */
    private static final long ON_AIR_MS = 180L * 24 * 3600 * 1000;

    private enum Kind { HOLIDAY, NEW, CLASSIC, REALITY }

    /** Adults: Bravo-style reality gets this many of each service's picks (about a quarter). */
    private static final int REALITY_PER_SERVICE = 2;
    private static final int BRAVO = 74;
    private static final int REALITY_GENRE = 10764;
    /** Talk shows and news are tagged reality-adjacent but aren't what anyone means by it. */
    private static final Set<Integer> NOT_REALITY = new HashSet<>(java.util.Arrays.asList(10767, 10763));
    private static final int WRESTLING_KEYWORD = 5970;
    /** Monarchy, British monarchy, royal family, British royal family, period drama. */
    private static final String ROYAL_KEYWORDS = "2471|220649|4833|215634|15060";
    /** At most this many of a service's picks are the account's own (continue, new season, watchlist). */
    private static final int PERSONAL_PER_SERVICE = 5;
    private static final int NEXT_PER_SERVICE = 3;
    private static final long DAY_MS = 24L * 3600 * 1000;

    /** A title that passed the rules, with what the final choice needs to know about it. */
    private static final class Candidate {
        final Pick pick;
        /** The first genre that isn't Animation, Family or Kids (Comedy, Adventure…), or 0. */
        final int flavor;

        Candidate(Pick pick, int flavor) {
            this.pick = pick;
            this.flavor = flavor;
        }
    }

    /**
     * {@code season} is null outside the holidays; {@code fullDay} makes every pick a holiday pick.
     * {@code taste} is the Trakt account, for Adults on a TV signed in to Trakt; null otherwise.
     */
    static List<Pick> fetchPicks(Audience audience, Season season, boolean fullDay, Set<String> hidden,
            Watchmode watchmode, Trakt.Taste taste) throws IOException, JSONException {
        Map<Kind, Integer> capacity = new java.util.EnumMap<>(Kind.class);
        int holiday = season == null ? 0 : fullDay ? PER_SERVICE : PER_SERVICE / 2;
        int reality = audience == Audience.ADULTS && holiday < PER_SERVICE ? REALITY_PER_SERVICE : 0;
        int rest = PER_SERVICE - holiday - reality;
        capacity.put(Kind.HOLIDAY, holiday);
        capacity.put(Kind.NEW, rest / 2);
        capacity.put(Kind.CLASSIC, rest - rest / 2);
        capacity.put(Kind.REALITY, reality);

        Set<String> seen = new HashSet<>(hidden);
        Map<Pick.Service, List<Pick>> personal = new java.util.EnumMap<>(Pick.Service.class);
        if (taste != null) {
            personal = personal(taste, audience, seen);
            // What they've seen comes back only as "next episode" or "new season", never as a plain pick.
            seen.addAll(taste.watched);
        }

        // Candidate lists, each feeding one kind of pool; pools are per (service, kind).
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (int i = 0; i < PROVIDERS.length; i++) {
            String where = "&watch_region=" + Config.REGION + "&with_watch_providers=" + PROVIDERS[i];
            if (holiday > 0) lists.put(Kind.HOLIDAY + "/" + i, interleave(discoverHoliday(audience, season, where)));
            if (holiday < PER_SERVICE) {
                lists.put(Kind.NEW + "/" + i, concat(watchmode.releases(PROVIDER_SERVICE[i]),
                        interleave(discoverNew(audience, where))));
                lists.put(Kind.CLASSIC + "/" + i, interleave(discoverClassics(audience, where)));
            }
        }
        // Stremio's titles are the ones none of the services carry: what arrived elsewhere (HBO Max, Hulu…),
        // trending, and recent popular overall.
        if (holiday > 0) lists.put(Kind.HOLIDAY + "/stremio", interleave(discoverHoliday(audience, season, "")));
        if (holiday < PER_SERVICE) {
            lists.put(Kind.NEW + "/stremio", concat(watchmode.releases(Pick.Service.STREMIO),
                    interleave(trending(), discoverNew(audience, ""))));
            lists.put(Kind.CLASSIC + "/stremio", interleave(discoverClassics(audience, "")));
        }
        if (taste != null && holiday < PER_SERVICE) lists.put(Kind.CLASSIC + "/trakt", taste.recommended);
        if (reality > 0) lists.put(Kind.REALITY + "/all", realityCandidates(taste));

        Map<String, List<Candidate>> pools = new LinkedHashMap<>();
        for (Pick.Service s : Pick.Service.values()) {
            for (Kind k : Kind.values()) pools.put(s + "/" + k, new ArrayList<>());
        }

        boolean progress = true;
        for (int round = 0; progress; round++) {
            progress = false;
            for (Map.Entry<String, List<String>> list : lists.entrySet()) {
                if (round >= list.getValue().size()) continue;
                progress = true;
                String candidate = list.getValue().get(round);
                if (!seen.add(candidate)) continue;
                Kind kind = Kind.valueOf(list.getKey().substring(0, list.getKey().indexOf('/')));
                if (!anyOpen(kind, capacity, pools)) continue;
                Candidate c = kind == Kind.REALITY ? realityDetails(candidate) : details(candidate, audience);
                if (c == null) continue;
                List<Candidate> pool = pools.get(c.pick.service + "/" + kind);
                if (pool.size() < capacity.get(kind) * POOL) {
                    pool.add(kind == Kind.HOLIDAY ? new Candidate(c.pick.asHoliday(), c.flavor) : c);
                }
            }
            if (allFull(capacity, pools)) break;
        }

        List<Pick> picks = new ArrayList<>();
        for (Pick.Service s : Pick.Service.values()) {
            List<Pick> own = personal.getOrDefault(s, new ArrayList<>());
            List<Pick> regular = choose(s, audience, capacity, pools);
            picks.addAll(own);
            picks.addAll(regular.subList(0, Math.max(0, Math.min(regular.size(), PER_SERVICE - own.size()))));
        }
        List<Pick> linked = new ArrayList<>();
        for (Pick p : picks) linked.add(p.service == Pick.Service.STREMIO ? p : p.withLink(watchmode.link(p)));
        return linked;
    }

    /**
     * One service's final list from its pools, kinds taking turns (holiday, new, classic). Each turn takes
     * the best-ranked candidate that keeps the movie/show split and the flavors in check; when none does,
     * the best one that keeps the split; then simply the best one left. A kind that runs short leaves its
     * turns to the others.
     */
    private static List<Pick> choose(Pick.Service service, Audience audience, Map<Kind, Integer> capacity,
            Map<String, List<Candidate>> pools) {
        // Little kids watch shows more than movies: Kids gets 5 shows and 3 movies, the others half and half.
        int maxMovies = audience == Audience.KIDS ? PER_SERVICE * 3 / 8 : PER_SERVICE / 2;
        int maxShows = PER_SERVICE - maxMovies;
        Map<Kind, List<Candidate>> left = new java.util.EnumMap<>(Kind.class);
        Map<Kind, Integer> taken = new java.util.EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            left.put(k, new ArrayList<>(pools.get(service + "/" + k)));
            taken.put(k, 0);
        }
        Map<Integer, Integer> flavors = new java.util.HashMap<>();
        int movies = 0;
        int shows = 0;
        List<Pick> out = new ArrayList<>();
        // First each kind up to its share; then, when a kind ran short (few new G titles), the others fill in.
        for (int pass = 0; pass < 2; pass++) {
            boolean shares = pass == 0;
            boolean progress = true;
            while (progress && out.size() < PER_SERVICE) {
                progress = false;
                for (Kind k : Kind.values()) {
                    List<Candidate> pool = left.get(k);
                    if (out.size() >= PER_SERVICE || pool.isEmpty() || capacity.get(k) == 0) continue;
                    if (shares && taken.get(k) >= capacity.get(k)) continue;
                    Candidate best = null;
                    for (int rule = 0; rule < 3 && best == null; rule++) {
                        for (Candidate c : pool) {
                            boolean splitOk = c.pick.series ? shows < maxShows : movies < maxMovies;
                            boolean flavorOk = flavors.getOrDefault(c.flavor, 0) < MAX_PER_FLAVOR;
                            if (rule == 2 || (splitOk && (rule == 1 || flavorOk))) {
                                best = c;
                                break;
                            }
                        }
                    }
                    pool.remove(best);
                    taken.put(k, taken.get(k) + 1);
                    flavors.merge(best.flavor, 1, Integer::sum);
                    if (best.pick.series) shows++;
                    else movies++;
                    out.add(best.pick);
                    progress = true;
                }
            }
        }
        return out;
    }

    /**
     * Movies and shows matching {@code query}, best match first, with the titles this audience may watch
     * ahead of the rest (nothing is left out). No quality bar: a search is for something you already want.
     */
    static List<Pick> search(String query, Audience audience) throws IOException, JSONException {
        JSONArray results = get("/search/multi?include_adult=false&language=en-US&query="
                + java.net.URLEncoder.encode(query, "UTF-8")).getJSONArray("results");
        List<String> found = new ArrayList<>();
        for (int i = 0; i < results.length() && found.size() < SEARCH_RESULTS; i++) {
            JSONObject r = results.getJSONObject(i);
            String type = r.optString("media_type");
            if (type.equals("movie") || type.equals("tv")) found.add(type + ":" + r.getInt("id"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(SEARCH_THREADS);
        try {
            List<Future<JSONObject>> details = new ArrayList<>();
            for (String candidate : found) details.add(pool.submit(() -> fetchDetails(candidate)));
            List<Pick> forAudience = new ArrayList<>();
            List<Pick> others = new ArrayList<>();
            for (int i = 0; i < found.size(); i++) {
                boolean series = found.get(i).startsWith("tv:");
                JSONObject d = details.get(i).get();
                if (d.isNull("backdrop_path")) continue;
                Pick pick = toPick(series, d);
                if (pick == null) continue;
                boolean allowed = audience.allows(series, rating(series, d), genreIds(d));
                (allowed ? forAudience : others).add(pick);
            }
            forAudience.addAll(others);
            return forAudience;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            if (e.getCause() instanceof JSONException) throw (JSONException) e.getCause();
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("search interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Any pool of this kind still open? Cheap check before spending a details request. */
    private static boolean anyOpen(Kind kind, Map<Kind, Integer> capacity, Map<String, List<Candidate>> pools) {
        for (Pick.Service s : Pick.Service.values()) {
            if (pools.get(s + "/" + kind).size() < capacity.get(kind) * POOL) return true;
        }
        return false;
    }

    private static boolean allFull(Map<Kind, Integer> capacity, Map<String, List<Candidate>> pools) {
        for (Pick.Service s : Pick.Service.values()) {
            for (Kind k : Kind.values()) if (pools.get(s + "/" + k).size() < capacity.get(k) * POOL) return false;
        }
        return true;
    }

    /** Popular titles tagged with the season's keywords: holiday viewing is mostly the favourites. */
    private static List<List<String>> discoverHoliday(Audience audience, Season season, String where)
            throws IOException, JSONException {
        String tagged = "&with_keywords=" + season.keywords + "&sort_by=popularity.desc";
        List<List<String>> out = new ArrayList<>();
        out.add(discover("movie", "/discover/movie?vote_count.gte=100" + tagged + movieFilters(audience) + where));
        out.add(discover("tv", "/discover/tv?vote_count.gte=20" + tagged + showFilters(audience) + where));
        return out;
    }

    /** Popular titles released in the last ~18 months; for Kids, preschool shows still on the air first. */
    private static List<List<String>> discoverNew(Audience audience, String where) throws IOException, JSONException {
        String since = date(System.currentTimeMillis() - NEW_WINDOW_MS);
        List<List<String>> out = new ArrayList<>();
        if (audience == Audience.KIDS) {
            out.add(discover("tv", "/discover/tv?sort_by=popularity.desc&with_genres=" + Audience.KIDS_GENRE
                    + "&air_date.gte=" + date(System.currentTimeMillis() - ON_AIR_MS)
                    + "&language=en-US&with_original_language=en" + where));
        }
        out.add(discover("movie", "/discover/movie?sort_by=popularity.desc&primary_release_date.gte=" + since
                + movieFilters(audience) + where));
        out.add(discover("tv", "/discover/tv?sort_by=popularity.desc&first_air_date.gte=" + since
                + showFilters(audience) + where));
        return out;
    }

    /** The best-rated titles of all time, with enough votes to mean it; for Kids, the favourite preschool shows first. */
    private static List<List<String>> discoverClassics(Audience audience, String where) throws IOException, JSONException {
        List<List<String>> out = new ArrayList<>();
        boolean kids = audience.discoverCertification != null;
        if (audience == Audience.ADULTS) {
            // British drama and royalty rank first: well-made, and a favourite in the house.
            out.add(discover("tv", "/discover/tv?sort_by=popularity.desc&with_origin_country=GB&with_genres=18"
                    + "&vote_count.gte=100&vote_average.gte=7" + showFilters(audience) + where));
            out.add(discover("tv", "/discover/tv?sort_by=popularity.desc&with_keywords=" + ROYAL_KEYWORDS
                    + "&vote_count.gte=50" + showFilters(audience) + where));
        }
        if (audience == Audience.KIDS) {
            out.add(discover("tv", "/discover/tv?sort_by=popularity.desc&with_genres=" + Audience.KIDS_GENRE
                    + "&vote_count.gte=" + MIN_KID_SHOW_VOTES + "&language=en-US&with_original_language=en" + where));
        }
        out.add(discover("movie", "/discover/movie?sort_by=vote_average.desc&vote_count.gte=1000"
                + "&primary_release_date.gte=" + CLASSICS_SINCE + movieFilters(audience) + where));
        out.add(discover("tv", "/discover/tv?sort_by=vote_average.desc&vote_count.gte=" + (kids ? 100 : 300)
                + "&first_air_date.gte=" + CLASSICS_SINCE + showFilters(audience) + where));
        return out;
    }

    private static List<List<String>> trending() throws IOException, JSONException {
        List<String> out = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            JSONArray results = get("/trending/all/week?language=en-US&page=" + page).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                if ((type.equals("movie") || type.equals("tv")) && r.optString("original_language").equals("en")) {
                    out.add(type + ":" + r.getInt("id"));
                }
            }
        }
        List<List<String>> wrapped = new ArrayList<>();
        wrapped.add(out);
        return wrapped;
    }

    private static String movieFilters(Audience audience) {
        String f = "&language=en-US&with_original_language=en&with_runtime.gte=" + MIN_RUNTIME;
        // "G" isn't "made for kids" (2001: A Space Odyssey is G): kids' movies are also animation or family.
        return audience.discoverCertification == null ? f
                : f + "&certification_country=US&certification=" + audience.discoverCertification
                        + "&with_genres=" + KID_MOVIE_GENRES;
    }

    private static String showFilters(Audience audience) {
        String f = "&language=en-US&with_original_language=en";
        return audience.discoverCertification == null ? f : f + "&with_genres=" + KID_SHOW_GENRES;
    }

    private static List<String> concat(List<String> first, List<String> then) {
        List<String> out = new ArrayList<>(first);
        out.addAll(then);
        return out;
    }

    @SafeVarargs
    private static List<String> interleave(List<List<String>>... groups) {
        List<List<String>> all = new ArrayList<>();
        for (List<List<String>> g : groups) all.addAll(g);
        List<String> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            boolean any = false;
            for (List<String> l : all) {
                if (i < l.size()) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) return out;
        }
    }

    /** Two pages of a discover query: the age and quality rules turn most of a page away. */
    private static List<String> discover(String type, String path) throws IOException, JSONException {
        List<String> out = new ArrayList<>();
        for (int page = 1; page <= DISCOVER_PAGES; page++) out.addAll(ids(type, get(path + "&page=" + page)));
        return out;
    }

    private static List<String> ids(String type, JSONObject page) throws JSONException {
        JSONArray results = page.getJSONArray("results");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) out.add(type + ":" + results.getJSONObject(i).getInt("id"));
        return out;
    }

    /**
     * The account's own picks per service, in this order: a new season that just came out, new seasons
     * coming, the next episode of shows in progress, then the watchlist. Each title once; all of them are
     * added to {@code seen}. No quality bar: these are their shows. Still only what this audience may watch
     * (the account holds the kids' viewing too).
     */
    private static Map<Pick.Service, List<Pick>> personal(Trakt.Taste taste, Audience audience, Set<String> seen)
            throws IOException, JSONException {
        long now = System.currentTimeMillis();
        List<Pick> out = new ArrayList<>();
        List<Trakt.Premiere> premieres = new ArrayList<>(taste.premieres);
        Collections.sort(premieres, (a, b) -> Long.compare(a.airsMs, b.airsMs));
        for (boolean out1 : new boolean[] {true, false}) {
            for (Trakt.Premiere p : premieres) {
                boolean isOut = p.airsMs <= now;
                if (isOut != out1 || seen.contains("tv:" + p.tmdbId)) continue;
                Pick pick = own("tv:" + p.tmdbId, audience);
                if (pick == null) continue;
                seen.add("tv:" + p.tmdbId);
                out.add(pick.withCaption(isOut ? "New season · Season " + p.season + " is out"
                        : "Season " + p.season + " · " + when(p.airsMs, now), isOut));
            }
        }
        Map<Pick.Service, Integer> nexts = new java.util.EnumMap<>(Pick.Service.class);
        for (Trakt.Next n : taste.next) {
            if (seen.contains("tv:" + n.tmdbId)) continue;
            Pick pick = own("tv:" + n.tmdbId, audience);
            if (pick == null || nexts.getOrDefault(pick.service, 0) >= NEXT_PER_SERVICE) continue;
            seen.add("tv:" + n.tmdbId);
            nexts.merge(pick.service, 1, Integer::sum);
            out.add(pick.withCaption("Next: S" + n.season + " E" + n.episode, false));
        }
        for (String w : taste.watchlist) {
            if (seen.contains(w)) continue;
            Pick pick = own(w, audience);
            if (pick == null) continue;
            seen.add(w);
            out.add(pick.withCaption("On your watchlist", false));
        }
        Map<Pick.Service, List<Pick>> byService = new java.util.EnumMap<>(Pick.Service.class);
        for (Pick p : out) {
            List<Pick> list = byService.computeIfAbsent(p.service, k -> new ArrayList<>());
            if (list.size() < PERSONAL_PER_SERVICE) list.add(p);
        }
        return byService;
    }

    /** "in 12 days", or the weekday ("Friday") within a week. */
    private static String when(long airsMs, long now) {
        long days = (airsMs - now + DAY_MS - 1) / DAY_MS;
        return days < 7 ? new SimpleDateFormat("EEEE", Locale.US).format(new Date(airsMs)) : "in " + days + " days";
    }

    /** One of the account's titles: needs a backdrop, this audience's OK, and somewhere to open it. */
    private static Pick own(String candidate, Audience audience) throws IOException, JSONException {
        boolean series = candidate.startsWith("tv:");
        JSONObject d = fetchDetails(candidate);
        if (d.isNull("backdrop_path")) return null;
        if (!audience.allows(series, rating(series, d), genreIds(d))) return null;
        return toPick(series, d);
    }

    /**
     * Reality for Adults: shows like the account's most-watched reality shows (TMDB's "more like this"),
     * then Bravo's. Without Trakt, Bravo's alone.
     */
    private static List<String> realityCandidates(Trakt.Taste taste) throws IOException, JSONException {
        List<List<String>> lists = new ArrayList<>();
        if (taste != null) {
            int seeds = 0;
            for (int id : taste.mostWatched) {
                if (seeds == 6) break;
                JSONObject d = get("/tv/" + id + "?language=en-US");
                if (!genreIds(d).contains(REALITY_GENRE)) continue;
                seeds++;
                lists.add(ids("tv", get("/tv/" + id + "/recommendations?language=en-US")));
            }
        }
        lists.add(discover("tv", "/discover/tv?sort_by=popularity.desc&with_networks=" + BRAVO
                + "&without_genres=10767&language=en-US&with_original_language=en"));
        return interleave(lists);
    }

    /** A reality show: English, the reality genre, not talk, news or wrestling. No rating bar (that's the point). */
    private static Candidate realityDetails(String candidate) throws IOException, JSONException {
        if (!candidate.startsWith("tv:")) return null;
        JSONObject d = fetchDetails(candidate);
        if (d.isNull("backdrop_path") || !d.optString("original_language").equals("en")) return null;
        Set<Integer> genres = genreIds(d);
        if (!genres.contains(REALITY_GENRE) || !Collections.disjoint(genres, NOT_REALITY)) return null;
        JSONArray keywords = d.getJSONObject("keywords").getJSONArray("results");
        for (int i = 0; i < keywords.length(); i++) {
            if (keywords.getJSONObject(i).getInt("id") == WRESTLING_KEYWORD) return null;
        }
        if (!Audience.ADULTS.allows(true, rating(true, d), genres)) return null;
        Pick pick = toPick(true, d);
        return pick == null ? null : new Candidate(pick, flavor(d));
    }

    /**
     * Null when the title is skipped: not for this audience, below the quality bar, a short, no backdrop,
     * or nowhere to open it (no dock service and no IMDb id for Stremio).
     */
    private static Candidate details(String candidate, Audience audience) throws IOException, JSONException {
        boolean series = candidate.startsWith("tv:");
        JSONObject d = fetchDetails(candidate);
        if (d.isNull("backdrop_path")) return null;
        if (!d.optString("original_language").equals("en")) return null;
        if (!series && d.optInt("runtime") < MIN_RUNTIME) return null;
        Set<Integer> genres = genreIds(d);
        if (!goodEnough(series, d, genres)) return null;
        if (!audience.allows(series, rating(series, d), genres)) return null;
        Pick pick = toPick(series, d);
        return pick == null ? null : new Candidate(pick, flavor(d));
    }

    private static int flavor(JSONObject d) throws JSONException {
        JSONArray genres = d.getJSONArray("genres");
        for (int i = 0; i < genres.length(); i++) {
            int id = genres.getJSONObject(i).getInt("id");
            if (!PLAIN_GENRES.contains(id)) return id;
        }
        return 0;
    }

    private static JSONObject fetchDetails(String candidate) throws IOException, JSONException {
        boolean series = candidate.startsWith("tv:");
        int id = Integer.parseInt(candidate.substring(candidate.indexOf(':') + 1));
        return get("/" + (series ? "tv" : "movie") + "/" + id
                + "?language=en-US&append_to_response=watch/providers,external_ids,images,"
                + (series ? "content_ratings" : "release_dates") + ",keywords&include_image_language=en,null");
    }

    /** The title and where it opens, or null when there's nowhere: no dock service and no IMDb id for Stremio. */
    private static Pick toPick(boolean series, JSONObject d) throws JSONException {
        int id = d.getInt("id");
        String imdb = series ? d.getJSONObject("external_ids").optString("imdb_id") : d.optString("imdb_id");
        if (imdb.equals("null")) imdb = "";

        Pick.Service service = Pick.Service.STREMIO;
        String elsewhere = "";
        JSONObject region = d.getJSONObject("watch/providers").getJSONObject("results").optJSONObject(Config.REGION);
        if (region != null) {
            List<String> names = new ArrayList<>();
            for (String k : new String[] {"flatrate", "ads", "free"}) {
                JSONArray list = region.optJSONArray(k);
                for (int i = 0; list != null && i < list.length(); i++) {
                    names.add(list.getJSONObject(i).getString("provider_name"));
                }
            }
            service = serviceOf(names);
            if (service == Pick.Service.STREMIO && !names.isEmpty()) elsewhere = names.get(0);
        }
        if (service == Pick.Service.STREMIO && imdb.isEmpty()) return null;

        String title = d.optString(series ? "name" : "title");
        return new Pick(series, id, title, facts(series, d), genres(d), d.optString("overview"),
                d.getString("backdrop_path"), logo(d), imdb, service, elsewhere, "", false, "", false);
    }

    private static boolean goodEnough(boolean series, JSONObject d, Set<Integer> genres) {
        String date = d.optString(series ? "first_air_date" : "release_date");
        boolean justOut = date.length() == 10
                && date.compareTo(date(System.currentTimeMillis() - JUST_OUT_MS)) >= 0;
        // Fewer people rate preschool shows: Bluey-type titles would never reach the usual bar.
        int minVotes = justOut ? MIN_JUST_OUT_VOTES
                : !series ? MIN_MOVIE_VOTES
                : genres.contains(Audience.KIDS_GENRE) ? MIN_KID_SHOW_VOTES : MIN_SHOW_VOTES;
        return d.optDouble("vote_average") >= MIN_RATING && d.optInt("vote_count") >= minVotes;
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

    /** The first two genres, "Animation, Comedy". */
    private static String genres(JSONObject d) throws JSONException {
        JSONArray genres = d.getJSONArray("genres");
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < Math.min(2, genres.length()); i++) {
            g.append(i == 0 ? "" : ", ").append(genres.getJSONObject(i).getString("name"));
        }
        return g.toString();
    }

    /** "Series · 2019 · 3 seasons" or "Movie · 2016 · 1h 47m". */
    private static String facts(boolean series, JSONObject d) throws JSONException {
        StringBuilder m = new StringBuilder(series ? "Series" : "Movie");
        String date = d.optString(series ? "first_air_date" : "release_date");
        if (date.length() >= 4) m.append(" · ").append(date, 0, 4);
        if (series) {
            int seasons = d.optInt("number_of_seasons");
            if (seasons > 0) m.append(" · ").append(seasons).append(seasons == 1 ? " season" : " seasons");
        } else {
            int runtime = d.optInt("runtime");
            if (runtime >= 60) m.append(" · ").append(runtime / 60).append("h ").append(runtime % 60).append("m");
            else if (runtime > 0) m.append(" · ").append(runtime).append("m");
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

    private static String date(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
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
