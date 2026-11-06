package tv.slick;

import java.util.Calendar;

/** Holidays that bring their own picks, for the {@link #LEAD_DAYS} days up to and including the day. */
enum Season {
    HALLOWEEN("Halloween", Calendar.OCTOBER, 31, "3335|318653", new int[][] {{Calendar.OCTOBER, 31}}),
    CHRISTMAS("Holiday", Calendar.DECEMBER, 25, "207317|260365",
            new int[][] {{Calendar.DECEMBER, 24}, {Calendar.DECEMBER, 25}});

    static final int LEAD_DAYS = 45;

    /** Caption word: "Holiday pick on Disney+". */
    final String label;
    private final int month;
    private final int day;
    /** TMDB keyword ids, OR-ed. */
    final String keywords;
    /** Days when every pick is a holiday pick. */
    private final int[][] fullDays;

    Season(String label, int month, int day, String keywords, int[][] fullDays) {
        this.label = label;
        this.month = month;
        this.day = day;
        this.keywords = keywords;
        this.fullDays = fullDays;
    }

    /** The season {@code nowMs} falls in, or null. */
    static Season at(long nowMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        for (Season s : values()) {
            Calendar end = Calendar.getInstance();
            end.clear();
            end.set(now.get(Calendar.YEAR), s.month, s.day);
            Calendar start = (Calendar) end.clone();
            start.add(Calendar.DAY_OF_YEAR, -LEAD_DAYS);
            end.add(Calendar.DAY_OF_YEAR, 1);
            if (!now.before(start) && now.before(end)) return s;
        }
        return null;
    }

    boolean isFullDay(long nowMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        for (int[] d : fullDays) {
            if (now.get(Calendar.MONTH) == d[0] && now.get(Calendar.DAY_OF_MONTH) == d[1]) return true;
        }
        return false;
    }
}
