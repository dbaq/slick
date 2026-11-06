package tv.slick;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Fire OS won't let a third-party app be Home, so send the Amazon home screen straight back to Slick. */
public class HomeGuard extends AccessibilityService {

    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";

    /**
     * Connecting happens at boot, after Slick is updated, and when the guard is switched on: each time
     * the Amazon home screen is already up (an update kills Slick) and no new event will announce it.
     */
    @Override
    protected void onServiceConnected() {
        goHome();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!AMAZON_HOME.contentEquals(event.getClassName())) return;
        goHome();
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    public void onInterrupt() {
    }
}
