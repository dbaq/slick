package tv.slick;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/** Opening a title in its app: shared by the home screen's picks and search. */
final class Titles {

    private Titles() {}

    /** The app's TV launch intent, or null when it isn't installed. */
    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        return launch != null ? launch : pm.getLaunchIntentForPackage(pkg);
    }

    /** Stremio's page for the title, the title's page in its app (Watchmode link), or the app's home. */
    static Intent intent(PackageManager pm, Pick p) {
        Intent intent;
        if (p.service == Pick.Service.STREMIO) {
            intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("stremio:///detail/" + (p.series ? "series" : "movie") + "/" + p.imdbId))
                    .setPackage(p.service.pkg);
        } else if (p.link.isEmpty()) {
            intent = launchIntent(pm, p.service.pkg);
        } else {
            // A fresh task: an app already running ignores a link handed to its old one.
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(p.link)).setPackage(p.service.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // Netflix's TV app only follows links that say they come from a partner launcher.
            if (p.service == Pick.Service.NETFLIX) intent.putExtra("source", "30");
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
