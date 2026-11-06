package tv.slick;

import java.util.Calendar;
import java.util.TimeZone;

/** Sunrise and sunset (NOAA's simplified algorithm, official zenith 90.833°). */
final class Sun {

    private static final double ZENITH = 90.833;

    static boolean isDay(double lat, double lon, long nowMs) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(nowMs);
        int dayOfYear = local.get(Calendar.DAY_OF_YEAR);

        Calendar utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utcMidnight.clear();
        utcMidnight.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        long base = utcMidnight.getTimeInMillis();

        double rise = utcHour(dayOfYear, lat, lon, true);
        double set = utcHour(dayOfYear, lat, lon, false);
        if (set < rise) set += 24;
        long riseMs = base + (long) (rise * 3_600_000);
        long setMs = base + (long) (set * 3_600_000);
        return nowMs >= riseMs && nowMs < setMs;
    }

    private static double utcHour(int dayOfYear, double lat, double lon, boolean rising) {
        double lngHour = lon / 15;
        double t = dayOfYear + ((rising ? 6 : 18) - lngHour) / 24;
        double m = 0.9856 * t - 3.289;
        double l = norm(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634, 360);
        double ra = norm(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l)))), 360);
        ra = (ra + (Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90)) / 15;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cos(ZENITH) - sinDec * sin(lat)) / (cosDec * cos(lat));
        double h = Math.toDegrees(Math.acos(cosH));
        if (rising) h = 360 - h;
        double local = h / 15 + ra - 0.06571 * t - 6.622;
        return norm(local - lngHour, 24);
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double norm(double v, double range) {
        return ((v % range) + range) % range;
    }
}
