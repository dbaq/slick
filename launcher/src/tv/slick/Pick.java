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
    final String meta;
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

    Pick(boolean series, int tmdbId, String title, String meta, String overview, String backdrop, String logo,
            String imdbId, Service service, String elsewhere, String link, boolean holiday) {
        this.series = series;
        this.tmdbId = tmdbId;
        this.title = title;
        this.meta = meta;
        this.overview = overview;
        this.backdrop = backdrop;
        this.logo = logo;
        this.imdbId = imdbId;
        this.service = service;
        this.elsewhere = elsewhere;
        this.link = link;
        this.holiday = holiday;
    }

    Pick withLink(String link) {
        return new Pick(series, tmdbId, title, meta, overview, backdrop, logo, imdbId, service, elsewhere, link, holiday);
    }

    Pick asHoliday() {
        return new Pick(series, tmdbId, title, meta, overview, backdrop, logo, imdbId, service, elsewhere, link, true);
    }

    /** The same title in Stremio, for when its service's app isn't on this TV. */
    Pick inStremio() {
        return new Pick(series, tmdbId, title, meta, overview, backdrop, logo, imdbId, Service.STREMIO, service.label,
                "", holiday);
    }

    String where() {
        if (service != Service.STREMIO) return service.label;
        return elsewhere.isEmpty() ? "Stremio" : elsewhere + " · Stremio";
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("series", series).put("tmdb", tmdbId).put("title", title).put("meta", meta).put("overview", overview)
                .put("backdrop", backdrop).put("logo", logo).put("imdb", imdbId)
                .put("service", service.name()).put("elsewhere", elsewhere).put("link", link)
                .put("holiday", holiday);
    }

    static Pick fromJson(JSONObject o) throws JSONException {
        return new Pick(o.getBoolean("series"), o.getInt("tmdb"), o.getString("title"), o.getString("meta"),
                o.getString("overview"), o.getString("backdrop"), o.getString("logo"), o.getString("imdb"),
                Service.valueOf(o.getString("service")), o.getString("elsewhere"), o.getString("link"),
                o.getBoolean("holiday"));
    }
}
