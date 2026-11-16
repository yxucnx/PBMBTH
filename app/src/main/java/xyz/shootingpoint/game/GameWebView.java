package xyz.shootingpoint.game;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.webkit.WebView;

/**
 * A full-screen WebView that hides the mouse cursor when pointer capture is held or requested, matching PC browser behavior.
 * Android WebView's internal Chromium engine normally renders CSS cursor styles (arrow/pointer) on hover
 * via onResolvePointerIcon. Overriding it here ensures the cursor sprite completely vanishes during gameplay.
 */
class GameWebView extends WebView {
    private boolean cursorHidden;

    public GameWebView(Context context) {
        super(context);
    }

    public GameWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GameWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setCursorHidden(boolean hidden) {
        if (cursorHidden != hidden) {
            cursorHidden = hidden;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setPointerIcon(PointerIcon.getSystemIcon(getContext(), hidden ? PointerIcon.TYPE_NULL : PointerIcon.TYPE_DEFAULT));
            }
        }
    }

    public boolean isCursorHidden() {
        return cursorHidden;
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        setCursorHidden(hasCapture);
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        boolean captured = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPointerCapture();
        if (cursorHidden || captured) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
            }
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }
}
