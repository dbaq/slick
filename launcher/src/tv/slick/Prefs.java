package tv.slick;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/** This TV's own choices, set in Slick Settings. */
final class Prefs {

    enum Theme { AUTO, LIGHT, DARK }

    /** First-run dock; Settings changes it per TV. */
    private static final String DEFAULT_DOCK = "com.disney.disneyplus,com.netflix.ninja,com.amazon.firebat,"
            + "org.smarttube.beta,com.stremio.one,com.plexapp.android";

    private final SharedPreferences p;

    Prefs(Context context) {
        p = context.getSharedPreferences("slick", Context.MODE_PRIVATE);
    }

    List<String> dock() {
        String raw = p.getString("dock", DEFAULT_DOCK);
        return raw.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    void setDock(List<String> packages) {
        p.edit().putString("dock", String.join(",", packages)).apply();
    }

    Theme theme() {
        return Theme.valueOf(p.getString("theme", Theme.AUTO.name()));
    }

    void setTheme(Theme t) {
        p.edit().putString("theme", t.name()).apply();
    }

    Audience dayAudience() {
        return Audience.valueOf(p.getString("day_audience", Audience.KIDS.name()));
    }

    void setDayAudience(Audience a) {
        p.edit().putString("day_audience", a.name()).apply();
    }

    Audience eveningAudience() {
        return Audience.valueOf(p.getString("evening_audience", Audience.ADULTS.name()));
    }

    void setEveningAudience(Audience a) {
        p.edit().putString("evening_audience", a.name()).apply();
    }

    /** Minutes after midnight. */
    int eveningStart() {
        return p.getInt("evening_start", 20 * 60);
    }

    void setEveningStart(int minutes) {
        p.edit().putInt("evening_start", minutes).apply();
    }

    int eveningEnd() {
        return p.getInt("evening_end", 6 * 60);
    }

    void setEveningEnd(int minutes) {
        p.edit().putInt("evening_end", minutes).apply();
    }

    Audience audienceAt(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        int t = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int start = eveningStart();
        int end = eveningEnd();
        boolean evening = start <= end ? t >= start && t < end : t >= start || t < end;
        return evening ? eveningAudience() : dayAudience();
    }

    static String clock(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " AM" : " PM");
    }
}
