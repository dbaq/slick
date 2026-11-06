package tv.slick;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Who the picks are for. Ratings are US: MPA for movies, TV Parental Guidelines for shows. */
enum Audience {
    KIDS("Kids", set("G"), set("TV-Y", "TV-G"), "G"),
    FAMILY("Family", set("G", "PG"), set("TV-Y", "TV-Y7", "TV-G", "TV-PG"), "G|PG"),
    /** Every rating, but not titles made for little kids. */
    ADULTS("Adults", null, null, null);

    private static final int FAMILY_GENRE = 10751;
    private static final int KIDS_GENRE = 10762;

    final String label;
    private final Set<String> movieRatings;
    private final Set<String> showRatings;
    /** TMDB discover's certification filter, or null for no filter. */
    final String discoverCertification;

    Audience(String label, Set<String> movieRatings, Set<String> showRatings, String discoverCertification) {
        this.label = label;
        this.movieRatings = movieRatings;
        this.showRatings = showRatings;
        this.discoverCertification = discoverCertification;
    }

    /** {@code rating} is "" when TMDB has none: unrated titles are only shown to adults. */
    boolean allows(boolean series, String rating, Set<Integer> genres) {
        if (this == ADULTS) {
            boolean forLittleKids = series
                    ? genres.contains(KIDS_GENRE) || rating.equals("TV-Y") || rating.equals("TV-Y7")
                    : genres.contains(FAMILY_GENRE) || rating.equals("G");
            return !forLittleKids;
        }
        return (series ? showRatings : movieRatings).contains(rating);
    }

    Audience next(int delta) {
        Audience[] all = values();
        return all[(ordinal() + delta + all.length) % all.length];
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
