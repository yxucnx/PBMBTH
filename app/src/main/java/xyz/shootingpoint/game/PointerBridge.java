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
 * A real mouse lock for the game page. Android WebView exposes requestPointerLock but always
 * refuses it natively, so the app captures the mouse itself (View.requestPointerCapture, Android 8+)
 * and hands the page raw movement over a WebMessagePort.
 *
 * Page -> app: "lock", "unlock".
 * App -> page: "l,1" / "l,0" (capture held / lost),
 *              "m,dx,dy" (relative movement counts, one message per frame),
 *              "b,button,1|0" (DOM button numbers),
 *              "w,1|-1" (wheel, DOM deltaY sign).
 *
 * The page side is web-client/src/escapeGuard.ts / nativePointer.ts.
 */
final class PointerBridge {
    private static final String ORIGIN = "https://shootingpoint.xyz";

    /**
     * Maximum allowed single-tick relative delta to discard wrap-around / coordinate jump glitches.
     */
    private static final float MAX_STEP_DELTA = 150.0f;

    private static final long[] OFFER_AGAIN_MS = {400, 800, 1200, 1600, 2000, 3000, 4000, 5000, 7000, 10000, 15000};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private WebMessagePort port;
    private float dx, dy;
    private boolean flushQueued;
    private boolean escapeHeld;
    private boolean answered;
    private int offers;
    private int dropInitialMoves;
    private boolean lastCaptureHeld;

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
        dropInitialMoves = 0;
        lastCaptureHeld = false;
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
        dropInitialMoves = 0;
        lastCaptureHeld = false;
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
        dropInitialMoves = 5;
        setCursorHidden(true);
        web.requestFocus();
        web.requestPointerCapture();
        if (web.hasPointerCapture()) {
            captureChanged(true);
        }
    }

    private void unlock() {
        dx = dy = 0;
        dropInitialMoves = 0;
        setCursorHidden(false);
        if (web != null && web.hasPointerCapture()) {
            web.releasePointerCapture();
        }
    }

    void captureChanged(boolean held) {
        if (lastCaptureHeld == held) return;
        lastCaptureHeld = held;
        dx = dy = 0;
        if (held) {
            dropInitialMoves = 5;
        }
        setCursorHidden(held);
        send(held ? "l,1" : "l,0");
    }

    private void setCursorHidden(boolean hidden) {
        if (web instanceof GameWebView) {
            ((GameWebView) web).setCursorHidden(hidden);
        } else if (web != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            web.setPointerIcon(PointerIcon.getSystemIcon(web.getContext(), hidden ? PointerIcon.TYPE_NULL : PointerIcon.TYPE_DEFAULT));
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
                if (dropInitialMoves > 0) {
                    dropInitialMoves--;
                    dx = 0;
                    dy = 0;
                    return true;
                }
                for (int i = 0; i < event.getHistorySize(); i++) {
                    accumulateDelta(getStepX(event, i), getStepY(event, i));
                }
                accumulateDelta(getStepX(event, -1), getStepY(event, -1));
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
        if (Math.abs(stepX) < MAX_STEP_DELTA) {
            dx += stepX;
        }
        if (Math.abs(stepY) < MAX_STEP_DELTA) {
            dy += stepY;
        }
    }

    private float getStepX(MotionEvent event, int historyIndex) {
        if (historyIndex >= 0) {
            return event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, historyIndex);
        } else {
            return event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        }
    }

    private float getStepY(MotionEvent event, int historyIndex) {
        if (historyIndex >= 0) {
            return event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, historyIndex);
        } else {
            return event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        }
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

    private static int domButton(int android) {
        switch (android) {
            case MotionEvent.BUTTON_PRIMARY: return 0;
            case MotionEvent.BUTTON_TERTIARY: return 1;
            case MotionEvent.BUTTON_SECONDARY: return 2;
            case MotionEvent.BUTTON_BACK: return 3;
            case MotionEvent.BUTTON_FORWARD: return 4;
            default: return -1;
        }
    }
}
