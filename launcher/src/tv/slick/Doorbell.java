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
import android.view.KeyEvent;
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
 * a second while the card is up. The card's window never takes focus, so playback carries on. While it's
 * up, OK enlarges it to the middle of the screen, the cameras side by side as big as fit and from their
 * sharp streams when they have one; OK shrinks it back and Back closes it. Enlarged, it stays until closed.
 * To try it
 * without a ring: adb shell am broadcast -a tv.slick.RING (only adb can send it).
 */
final class Doorbell {

    private static final String TAG = "Slick";
    private static final long WATCH_MS = 1000;
    private static final long FRAME_MS = 500;
    private static final long SHOW_MS = 45_000;
    /** The small card stacks the cameras at this width; their heights follow each camera's shape. */
    private static final int SMALL_WIDTH_DP = 200;
    private static final int ENLARGED_MAX_HEIGHT_DP = 420;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler network;
    private final Runnable watch = this::watch;
    private final Runnable frame = this::frame;
    private final Runnable hide = this::hide;
    private boolean ringing;
    private LinearLayout card;
    private TextView when;
    private TextView hint;
    private LinearLayout row;
    private final List<ImageView> pictures = new ArrayList<>();
    /** Each camera's width over height, once its first picture has come in. */
    private final List<Float> shapes = new ArrayList<>();
    private boolean enlarged;
    /** The press that acted on the card: its release mustn't reach the app either. */
    private int swallowUp = KeyEvent.KEYCODE_UNKNOWN;

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

    /** The remote while the card is up: OK and Back belong to the card; the rest to the app. */
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!down) {
            if (code != swallowUp) return false;
            swallowUp = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (card == null) return false;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                main.removeCallbacks(hide);
                hide();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) setEnlarged(!enlarged);
                break;
            default:
                return false;
        }
        swallowUp = code;
        return true;
    }

    private void setEnlarged(boolean on) {
        enlarged = on;
        // Enlarged, it stays until closed; back in the corner, it gets its time again.
        main.removeCallbacks(hide);
        if (!on) main.postDelayed(hide, SHOW_MS);
        layout();
        network.removeCallbacks(frame);
        network.post(frame);
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
        boolean big = enlarged;
        int heightPx = dp(big ? ENLARGED_MAX_HEIGHT_DP : SMALL_WIDTH_DP);
        for (int i = 0; i < cameras.size(); i++) {
            // Enlarged, the sharp stream when the camera has one.
            String[] streams = cameras.get(i).split("\\|");
            String camera = big ? streams[streams.length - 1] : streams[0];
            int at = i;
            try {
                byte[] jpeg = home.snapshot(camera);
                Bitmap b = decode(jpeg, heightPx);
                main.post(() -> {
                    if (card == null || at >= pictures.size()) return;
                    // Each camera keeps its own shape: the doorbell is 4:3, the front door cam 16:9.
                    float shape = (float) b.getWidth() / b.getHeight();
                    pictures.get(at).setImageBitmap(b);
                    if (shapes.get(at) != shape) {
                        shapes.set(at, shape);
                        layout();
                    }
                });
            } catch (IOException e) {
                Log.w(TAG, "doorbell camera unavailable: " + camera, e);
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
            enlarged = false;
            context.getSystemService(WindowManager.class).addView(card, wp);
            layout();
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
        shapes.clear();
        enlarged = false;
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
        when.setPadding(dp(12), 0, 0, 0);
        header.addView(when);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(10);
        box.addView(header, hp);

        row = new LinearLayout(context);
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
            row.addView(picture, new LinearLayout.LayoutParams(0, 0));
            pictures.add(picture);
            // 16:9 until the first picture says the camera's real shape.
            shapes.add(16f / 9);
        }
        box.addView(row);
        // Under the pictures, so the small card can be as narrow as its cameras.
        hint = SettingsActivity.Ui.text(context, "", 12, SettingsActivity.Ui.REGULAR, 0x80FFFFFF);
        hint.setPadding(dp(2), dp(8), 0, 0);
        box.addView(hint);
        return box;
    }

    /** Sizes and places the card: every camera small in the corner, or all of them large in the middle. */
    private void layout() {
        if (card == null) return;
        // Small: stacked in a column at one width. Enlarged: side by side, as tall as the screen's width allows.
        row.setOrientation(enlarged ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int height = 0;
        if (enlarged) {
            float shapesWide = 0;
            for (float shape : shapes) shapesWide += shape;
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            int room = screen - dp(2 * 16 + 2 * 14 + 10 * (pictures.size() - 1));
            height = Math.min(dp(ENLARGED_MAX_HEIGHT_DP), Math.round(room / shapesWide));
        }
        for (int i = 0; i < pictures.size(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pictures.get(i).getLayoutParams();
            lp.width = enlarged ? Math.round(height * shapes.get(i)) : dp(SMALL_WIDTH_DP);
            lp.height = enlarged ? height : Math.round(dp(SMALL_WIDTH_DP) / shapes.get(i));
            lp.leftMargin = enlarged && i > 0 ? dp(10) : 0;
            lp.topMargin = !enlarged && i > 0 ? dp(8) : 0;
            pictures.get(i).setLayoutParams(lp);
        }
        hint.setText(enlarged ? "OK to shrink  ·  Back to close" : "OK to enlarge  ·  Back to close");
        WindowManager.LayoutParams wp = (WindowManager.LayoutParams) card.getLayoutParams();
        wp.gravity = enlarged ? Gravity.CENTER : Gravity.TOP | Gravity.END;
        wp.x = enlarged ? 0 : dp(28);
        wp.y = enlarged ? 0 : dp(28);
        context.getSystemService(WindowManager.class).updateViewLayout(card, wp);
    }

    /** Decodes a camera picture at about the size it's shown: a 4K frame at full size would be 33 MB. */
    private static Bitmap decode(byte[] jpeg, int heightPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        while (o.outHeight / (sample * 2) >= heightPx) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    private int dp(int v) {
        return SettingsActivity.Ui.dp(context, v);
    }
}
