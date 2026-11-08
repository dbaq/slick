package tv.slick;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

/**
 * Fire OS won't let a third-party app be Home, so send the Amazon home screen straight back to Slick.
 * The guard also sees every remote press first, in any app: ☰ (Menu) opens the Home Assistant panel on
 * top of whatever is playing. The panel's window never takes focus, so the app underneath keeps playing;
 * while it's open the guard hands the remote's presses to the panel instead of the app. It also runs the
 * {@link Doorbell} card, which Back puts away.
 */
public class HomeGuard extends AccessibilityService {

    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    /** The panel's slide-out, before its window goes away. */
    private static final long CLOSE_MS = 250;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout overlay;
    private HomePanel panel;
    private Doorbell doorbell;
    /** The Back that dismissed the doorbell card: its release mustn't reach the app either. */
    private boolean swallowBackUp;

    /**
     * Connecting happens at boot, after Slick is updated, and when the guard is switched on: each time
     * the Amazon home screen is already up (an update kills Slick) and no new event will announce it.
     */
    @Override
    protected void onServiceConnected() {
        if (doorbell == null) doorbell = new Doorbell(this);
        goHome();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!AMAZON_HOME.contentEquals(event.getClassName())) return;
        goHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        boolean open = panel != null && panel.isOpen();
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (!open && Home.load(this) == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (open) closePanel();
                else openPanel();
            }
            return true;
        }
        if (!open && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && doorbell != null && doorbell.isShowing()) {
                doorbell.dismiss();
                swallowBackUp = true;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && swallowBackUp) {
                swallowBackUp = false;
                return true;
            }
        }
        if (!open) return false;
        // Home leaves the panel behind; everything else belongs to the panel while it's up.
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            closePanel();
            return false;
        }
        if (panel.dispatchKeyEvent(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) closePanel();
        return true;
    }

    private void openPanel() {
        Home home = Home.load(this);
        main.removeCallbacksAndMessages(null);
        if (overlay == null) {
            panel = new HomePanel(this, home);
            overlay = new FrameLayout(this);
            int margin = SettingsActivity.Ui.dp(this, 28);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(SettingsActivity.Ui.dp(this, 400), -1, Gravity.END);
            lp.setMargins(0, margin, margin, margin);
            overlay.addView(panel, lp);
            WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            getSystemService(WindowManager.class).addView(overlay, wp);
        } else {
            panel.setHome(home);
        }
        panel.open();
    }

    private void closePanel() {
        panel.close();
        // Take the window away once the panel has slid out: nothing should sit over the video.
        main.postDelayed(() -> {
            if (overlay == null || panel.isOpen()) return;
            getSystemService(WindowManager.class).removeView(overlay);
            overlay = null;
            panel = null;
        }, CLOSE_MS);
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    public void onInterrupt() {
    }
}
