package tv.slick;

import org.json.JSONException;
import org.json.JSONObject;

/** One trending title, and the app it opens in. */
final class Pick {

    /** Which dock app a pick belongs to. Anything not on these opens in Stremio. */
    enum Service {
        DISNEY("com.disney.disneyplus", "Disney+"),
        NETFLIX("com.netflix.ninja", "Netflix"),
        PRIME("com.amazon.firebat", "Prime Video"),
        STREMIO("com.stremio.one", "Stremio");

        final String pkg;
        final String label;

        Service(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        static Service ofPackage(String pkg) {
            for (Service s : values()) if (s.pkg.equals(pkg)) return s;
            return null;
        }
    }

    final boolean series;
    final int tmdbId;
    final String title;
    /** "Series · 2019 · 3 seasons" or "Movie · 2016 · 1h 47m". */
    final String facts;
    /** "Animation, Comedy", or "". */
    final String genres;
    final String overview;
    final String backdrop;
    final String logo;
    final String imdbId;
    final Service service;
    /** Where it really streams when that isn't one of the dock's services, e.g. "HBO Max". */
    final String elsewhere;
    /** The title's page in its app (from Watchmode), or "" to open the app's home. */
    final String link;
    /** One of the season's picks (Christmas, Halloween). */
    final boolean holiday;
    /** Shown instead of the facts under the dock: "Next: S2 E5", "Season 3 · in 12 days"; "" for none. */
    final String caption;
    /** A new season that just came out: Slick opens on it once a day. */
    final boolean lead;

    Pick(boolean series, int tmdbId, String title, String facts, String genres, String overview, String backdrop, String logo,
            String imdbId, Service service, String elsewhere, String link, boolean holiday,
            String caption, boolean lead) {
        this.series = series;
        this.tmdbId = tmdbId;
        this.title = title;
        this.facts = facts;
        this.genres = genres;
        this.overview = overview;
        this.backdrop = backdrop;
        this.logo = logo;
        this.imdbId = imdbId;
        this.service = service;
        this.elsewhere = elsewhere;
        this.link = link;
        this.holiday = holiday;
        this.caption = caption;
        this.lead = lead;
    }

    Pick withLink(String link) {
        return new Pick(series, tmdbId, title, facts, genres, overview, backdrop, logo, imdbId, service, elsewhere, link, holiday,
                caption, lead);
    }

    Pick asHoliday() {
        return new Pick(series, tmdbId, title, facts, genres, overview, backdrop, logo, imdbId, service, elsewhere, link, true,
                caption, lead);
    }

    /** One of the account's own picks (Trakt), with what to say about it. */
    Pick withCaption(String caption, boolean lead) {
        return new Pick(series, tmdbId, title, facts, genres, overview, backdrop, logo, imdbId, service, elsewhere, link, holiday,
                caption, lead);
    }

    /** The same title in Stremio, for when its service's app isn't on this TV. */
    Pick inStremio() {
        return new Pick(series, tmdbId, title, facts, genres, overview, backdrop, logo, imdbId, Service.STREMIO, service.label,
                "", holiday, caption, lead);
    }

    /** "Series · Animation, Comedy · 2019 · 3 seasons": the facts with the genres after the kind. */
    String meta() {
        if (genres.isEmpty()) return facts;
        int kindEnd = facts.indexOf(" · ");
        return kindEnd < 0 ? facts + " · " + genres
                : facts.substring(0, kindEnd) + " · " + genres + facts.substring(kindEnd);
    }

    String where() {
        if (service != Service.STREMIO) return service.label;
        return elsewhere.isEmpty() ? "Stremio" : elsewhere + " · Stremio";
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("series", series).put("tmdb", tmdbId).put("title", title).put("facts", facts).put("genres", genres).put("overview", overview)
                .put("backdrop", backdrop).put("logo", logo).put("imdb", imdbId)
                .put("service", service.name()).put("elsewhere", elsewhere).put("link", link)
                .put("holiday", holiday).put("caption", caption).put("lead", lead);
    }

    static Pick fromJson(JSONObject o) throws JSONException {
        return new Pick(o.getBoolean("series"), o.getInt("tmdb"), o.getString("title"), o.getString("facts"), o.getString("genres"),
                o.getString("overview"), o.getString("backdrop"), o.getString("logo"), o.getString("imdb"),
                Service.valueOf(o.getString("service")), o.getString("elsewhere"), o.getString("link"),
                o.getBoolean("holiday"), o.getString("caption"), o.getBoolean("lead"));
    }
}
