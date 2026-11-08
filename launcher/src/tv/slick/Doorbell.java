package tv.slick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.json.JSONException;

/**
 * When the doorbell rings, a card in the top-right corner shows its cameras over whatever is playing, for
 * {@link #SHOW_MS}. Watches the ring sensor once a second while the screen is on; the pictures refresh twice
 * a second while the card is up. The card's window never takes focus, so playback carries on. To try it
 * without a ring: adb shell am broadcast -a tv.slick.RING (only adb can send it).
 */
final class Doorbell {

    private static final String TAG = "Slick";
    private static final long WATCH_MS = 1000;
    private static final long FRAME_MS = 500;
    private static final long SHOW_MS = 45_000;
    private static final int PICTURE_HEIGHT_DP = 180;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler network;
    private final Runnable watch = this::watch;
    private final Runnable frame = this::frame;
    private final Runnable hide = this::hide;
    private boolean ringing;
    private LinearLayout card;
    private TextView when;
    private final List<ImageView> pictures = new ArrayList<>();

    Doorbell(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("doorbell");
        thread.start();
        network = new Handler(thread.getLooper());
        network.post(watch);
        // The sender needs WRITE_SECURE_SETTINGS, which the adb shell has and ordinary apps don't.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Home home = Home.load(context);
                if (home != null && !home.doorbell.isEmpty()) show(home);
            }
        }, new IntentFilter("tv.slick.RING"), android.Manifest.permission.WRITE_SECURE_SETTINGS, main);
    }

    boolean isShowing() {
        return card != null;
    }

    /** Back on the remote: put the card away before its time is up. */
    void dismiss() {
        main.removeCallbacks(hide);
        hide();
    }

    // ---- Watching (network thread) -----------------------------------------------------------------

    private void watch() {
        network.postDelayed(watch, WATCH_MS);
        if (!context.getSystemService(PowerManager.class).isInteractive()) return;
        Home home = Home.load(context);
        if (home == null || home.doorbell.isEmpty()) return;
        try {
            boolean on = home.state(home.doorbell.get(0)).getString("state").equals("on");
            // The sensor stays on for about 15 seconds per ring: show once, when it turns on.
            if (on && !ringing) main.post(() -> show(home));
            ringing = on;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "doorbell sensor unreadable", e);
        }
    }

    private void frame() {
        Home home = Home.load(context);
        if (home == null) return;
        List<String> cameras = home.doorbell.subList(1, home.doorbell.size());
        for (int i = 0; i < cameras.size(); i++) {
            int at = i;
            try {
                byte[] jpeg = home.snapshot(cameras.get(i));
                Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                main.post(() -> {
                    if (card == null || at >= pictures.size()) return;
                    ImageView picture = pictures.get(at);
                    // Each camera keeps its own shape: the doorbell is 4:3, the front door cam 16:9.
                    int width = dp(PICTURE_HEIGHT_DP) * b.getWidth() / b.getHeight();
                    if (picture.getLayoutParams().width != width) {
                        picture.getLayoutParams().width = width;
                        picture.requestLayout();
                    }
                    picture.setImageBitmap(b);
                });
            } catch (IOException e) {
                Log.w(TAG, "doorbell camera unavailable: " + cameras.get(i), e);
            }
        }
        network.postDelayed(frame, FRAME_MS);
    }

    // ---- The card (main thread) --------------------------------------------------------------------

    private void show(Home home) {
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_MS);
        if (card == null) {
            card = build(home.doorbell.size() - 1);
            WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            wp.gravity = Gravity.TOP | Gravity.END;
            wp.x = dp(28);
            wp.y = dp(28);
            context.getSystemService(WindowManager.class).addView(card, wp);
            card.setAlpha(0f);
            card.setTranslationY(-dp(16));
            card.animate().alpha(1f).translationY(0).setDuration(220).start();
            network.removeCallbacks(frame);
            network.post(frame);
        }
        when.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
    }

    private void hide() {
        if (card == null) return;
        network.removeCallbacks(frame);
        View gone = card;
        card = null;
        pictures.clear();
        gone.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> context.getSystemService(WindowManager.class).removeView(gone)).start();
    }

    private LinearLayout build(int cameras) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xFF1A1B20);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        box.setBackground(bg);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = SettingsActivity.Ui.text(context, "Someone's at the door", 15, SettingsActivity.Ui.MEDIUM, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        when = SettingsActivity.Ui.text(context, "", 13, SettingsActivity.Ui.REGULAR, 0x99FFFFFF);
        header.addView(when);
        TextView back = SettingsActivity.Ui.text(context, "  ·  Back to close", 13, SettingsActivity.Ui.REGULAR, 0x66FFFFFF);
        header.addView(back);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(10);
        box.addView(header, hp);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < cameras; i++) {
            ImageView picture = new ImageView(context);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackgroundColor(0xFF000000);
            picture.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            picture.setClipToOutline(true);
            // A dark 16:9 placeholder until the first picture says the camera's real shape.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(PICTURE_HEIGHT_DP * 16 / 9), dp(PICTURE_HEIGHT_DP));
            if (i > 0) lp.leftMargin = dp(10);
            row.addView(picture, lp);
            pictures.add(picture);
        }
        box.addView(row);
        return box;
    }

    private int dp(int v) {
        return SettingsActivity.Ui.dp(context, v);
    }
}
