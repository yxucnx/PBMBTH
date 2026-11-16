package xyz.shootingpoint.game;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;

/**
 * Mouse lock bridge for Shooting Point (Android).
 *
 * Android WebView ignores requestPointerLock, so the app captures the pointer itself
 * (View.requestPointerCapture, Android 8+) and feeds raw deltas to the game page over a
 * WebMessagePort (escapeGuard.ts on the page side).
 *
 * Protocol (app -> page): "l,1" / "l,0" (lock/unlock), "m,dx,dy" (movement), "b,btn,1|0" (button), "w,1|-1" (wheel).
 * Protocol (page -> app): "lock", "unlock".
 *
 * Delta strategy (2.2.4):
 *   1. Try AXIS_RELATIVE_X/Y (proper relative hardware axis).
 *   2. If ALL samples for a full event have AXIS_RELATIVE_X==0 AND AXIS_RELATIVE_Y==0,
 *      fall back to position delta (currentRawX - lastRawX) — covers devices that deliver
 *      absolute coordinates even under pointer capture.
 *   3. After falling back, scale position-delta by POSITION_DELTA_SCALE to match typical
 *      browser movementX sensitivity (raw screen pixels are ~3-5x too coarse).
 *   4. Hard-clamp any single step at MAX_STEP_DELTA to discard wrap-around / boot spikes.
 *   5. Drop the first DROP_FRAMES frames after acquiring capture to skip the transition spike.
 */
final class PointerBridge {
    private static final String ORIGIN = "https://shootingpoint.xyz";

    /** Clamp for a single axis step — protects against coordinate wrap-around glitches. */
    private static final float MAX_STEP_DELTA = 200.0f;

    /**
     * When falling back to position-delta (absolute coordinates minus previous position),
     * scale down because a raw screen-pixel delta is 3-5x larger than a typical browser
     * movementX value for the same physical mouse distance.
     */
    private static final float POSITION_DELTA_SCALE = 0.35f;

    /** Discard this many motion events right after pointer capture to skip the transition spike. */
    private static final int DROP_FRAMES = 6;

    private static final long[] OFFER_AGAIN_MS = {400, 800, 1200, 1600, 2000, 3000, 4000, 5000, 7000, 10000, 15000};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private WebMessagePort port;
    private float dx, dy;
    private boolean flushQueued;
    private boolean escapeHeld;
    private boolean answered;
    private int offers;
    private int dropFrames;
    private boolean lastCaptureHeld;

    // Position-delta fallback: last raw position seen under capture.
    private float lastRawX = Float.NaN;
    private float lastRawY = Float.NaN;

    private final Choreographer.FrameCallback flushOnFrame = time -> {
        flushQueued = false;
        flush();
    };

    private final Runnable offerAgain = this::offerAgain;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    void attach(WebView view) {
        main.removeCallbacks(offerAgain);
        web = view;
        port = null;
        answered = false;
        dx = dy = 0;
        dropFrames = 0;
        lastCaptureHeld = false;
        resetPositionTracking();
        if (!supported()) return;
        view.setOnCapturedPointerListener((v, event) -> onCaptured(event));
    }

    void destroy() {
        main.removeCallbacks(offerAgain);
        closePort();
        setCursorHidden(false);
        web = null;
    }

    void pageFinished(WebView view, String url) {
        if (!supported() || view != web || url == null || !url.startsWith(ORIGIN + "/")) return;
        offers = 0;
        offerPort(view);
    }

    private void offerPort(WebView view) {
        main.removeCallbacks(offerAgain);
        closePort();
        answered = false;
        WebMessagePort[] channel = view.createWebMessageChannel();
        port = channel[0];
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override
            public void onMessage(WebMessagePort from, WebMessage message) {
                if (from != port) return;
                answered = true;
                main.removeCallbacks(offerAgain);
                if ("lock".equals(message.getData())) lock();
                else if ("unlock".equals(message.getData())) unlock();
            }
        }, main);
        view.postWebMessage(new WebMessage("pbmb-pointer", new WebMessagePort[]{channel[1]}), Uri.parse(ORIGIN));
        if (offers < OFFER_AGAIN_MS.length) main.postDelayed(offerAgain, OFFER_AGAIN_MS[offers]);
    }

    private void offerAgain() {
        if (!supported() || web == null || answered) return;
        if (web.hasPointerCapture()) return;
        offers++;
        offerPort(web);
    }

    void pageStarted() {
        main.removeCallbacks(offerAgain);
        closePort();
        answered = false;
        dx = dy = 0;
        dropFrames = 0;
        lastCaptureHeld = false;
        resetPositionTracking();
        setCursorHidden(false);
        if (supported() && web != null && web.hasPointerCapture()) {
            web.releasePointerCapture();
        }
    }

    private void closePort() {
        if (port != null) port.close();
        port = null;
        dx = dy = 0;
    }

    private void lock() {
        if (web == null) return;
        if (web.hasPointerCapture()) {
            send("l,1");
            return;
        }
        dx = dy = 0;
        dropFrames = DROP_FRAMES;
        resetPositionTracking();
        setCursorHidden(true);
        web.requestFocus();
        web.requestPointerCapture();
        // On some devices requestPointerCapture is synchronous — notify immediately.
        if (web.hasPointerCapture() && !lastCaptureHeld) {
            lastCaptureHeld = true;
            send("l,1");
        }
    }

    private void unlock() {
        dx = dy = 0;
        dropFrames = 0;
        resetPositionTracking();
        setCursorHidden(false);
        if (web != null && web.hasPointerCapture()) {
            web.releasePointerCapture();
        }
    }

    /** Called from Activity.onPointerCaptureChanged AND GameWebView.onPointerCaptureChange. */
    void captureChanged(boolean held) {
        if (lastCaptureHeld == held) return;
        lastCaptureHeld = held;
        dx = dy = 0;
        if (held) {
            dropFrames = DROP_FRAMES;
            resetPositionTracking();
        }
        setCursorHidden(held);
        send(held ? "l,1" : "l,0");
    }

    private void setCursorHidden(boolean hidden) {
        if (web instanceof GameWebView) {
            ((GameWebView) web).setCursorHidden(hidden);
        } else if (web != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            web.setPointerIcon(PointerIcon.getSystemIcon(web.getContext(),
                    hidden ? PointerIcon.TYPE_NULL : PointerIcon.TYPE_DEFAULT));
        }
    }

    boolean onKey(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE || !supported() || web == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && web.hasPointerCapture()) {
            escapeHeld = true;
            web.releasePointerCapture();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && escapeHeld) {
            escapeHeld = false;
            return true;
        }
        return false;
    }

    private boolean onCaptured(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (dropFrames > 0) {
                    dropFrames--;
                    dx = 0;
                    dy = 0;
                    // Reset position tracking after drop period so first real delta is correct.
                    resetPositionTracking();
                    return true;
                }

                // --- Collect all historical + current steps ---
                int histCount = event.getHistorySize();
                float[] stepX = new float[histCount + 1];
                float[] stepY = new float[histCount + 1];
                float[] rawX  = new float[histCount + 1];
                float[] rawY  = new float[histCount + 1];
                for (int i = 0; i < histCount; i++) {
                    stepX[i] = event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i);
                    stepY[i] = event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i);
                    rawX[i]  = event.getHistoricalX(i);
                    rawY[i]  = event.getHistoricalY(i);
                }
                stepX[histCount] = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                stepY[histCount] = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
                rawX[histCount]  = event.getX();
                rawY[histCount]  = event.getY();

                // --- Decide: use relative axes or fall back to position delta? ---
                boolean anyRelativeData = false;
                for (int i = 0; i <= histCount; i++) {
                    if (stepX[i] != 0 || stepY[i] != 0) {
                        anyRelativeData = true;
                        break;
                    }
                }

                if (anyRelativeData) {
                    // Device supports AXIS_RELATIVE: use it directly.
                    lastRawX = rawX[histCount];
                    lastRawY = rawY[histCount];
                    for (int i = 0; i <= histCount; i++) {
                        accumulateDelta(stepX[i], stepY[i]);
                    }
                } else {
                    // Device delivers absolute coords even under capture: compute position delta.
                    for (int i = 0; i <= histCount; i++) {
                        float cx = rawX[i];
                        float cy = rawY[i];
                        if (!Float.isNaN(lastRawX)) {
                            float ddx = (cx - lastRawX) * POSITION_DELTA_SCALE;
                            float ddy = (cy - lastRawY) * POSITION_DELTA_SCALE;
                            accumulateDelta(ddx, ddy);
                        }
                        lastRawX = cx;
                        lastRawY = cy;
                    }
                }

                if (!flushQueued && (dx != 0 || dy != 0)) {
                    flushQueued = true;
                    Choreographer.getInstance().postFrameCallback(flushOnFrame);
                }
                return true;
            }

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                int button = domButton(event.getActionButton());
                flush();
                if (button >= 0) {
                    send("b," + button + (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS ? ",1" : ",0"));
                }
                return true;
            }

            case MotionEvent.ACTION_SCROLL: {
                float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) send(v > 0 ? "w,-1" : "w,1");
                return true;
            }

            default:
                return true;
        }
    }

    private void accumulateDelta(float stepX, float stepY) {
        if (Math.abs(stepX) < MAX_STEP_DELTA) dx += stepX;
        if (Math.abs(stepY) < MAX_STEP_DELTA) dy += stepY;
    }

    private void flush() {
        int x = (int) dx, y = (int) dy;
        if (x == 0 && y == 0) return;
        dx -= x;
        dy -= y;
        send("m," + x + "," + y);
    }

    private void send(String text) {
        if (port == null) return;
        try {
            port.postMessage(new WebMessage(text));
        } catch (IllegalStateException e) {
            port = null;
        }
    }

    private void resetPositionTracking() {
        lastRawX = Float.NaN;
        lastRawY = Float.NaN;
    }

    private static int domButton(int android) {
        switch (android) {
            case MotionEvent.BUTTON_PRIMARY:   return 0;
            case MotionEvent.BUTTON_TERTIARY:  return 1;
            case MotionEvent.BUTTON_SECONDARY: return 2;
            case MotionEvent.BUTTON_BACK:      return 3;
            case MotionEvent.BUTTON_FORWARD:   return 4;
            default:                           return -1;
        }
    }
}
