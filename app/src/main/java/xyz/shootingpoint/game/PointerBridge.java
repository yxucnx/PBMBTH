package xyz.shootingpoint.game;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;

/**
 * A real mouse lock for the game page (2026-11-12, owner: "in the app the mouse is erratic"). Android WebView exposes
 * requestPointerLock but always refuses it, so a phone's mouse stayed a free pointer and the page fell back to turning at the screen
 * edge. Here the app captures the mouse itself (View.requestPointerCapture, Android 8+) and hands the page raw movement over a
 * WebMessagePort, not as DOM events: the anti-cheat counts synthetic input events.
 *
 * Page -> app: "lock", "unlock". App -> page: "l,1" / "l,0" (capture held / lost), "m,dx,dy" (movement, whole counts, one message per
 * frame), "b,button,1|0" (DOM button numbers), "w,1|-1" (wheel, DOM deltaY sign). The page side is web-client/src/nativePointer.ts.
 * Esc while captured ends the capture and is not passed on, as a desktop browser does with its lock.
 */
final class PointerBridge {
    private static final String ORIGIN = "https://shootingpoint.xyz";

    /**
     * Maximum allowed delta per step (in pixels) to reject boundary wrap / re-centering glitches.
     * Legitimate single-tick movement never approaches this threshold.
     */
    private static final float MAX_STEP_DELTA = 350.0f;

    /**
     * (2.2.1, 2026-11-16, owner: "connect a mouse and the view spins") onPageFinished comes right after the page's load event, but the page
     * starts listening for the port only when its boot module has run, which waits for the asset inventory and the asset-source answer
     * (a second or more on a phone). A message posted before that is lost, so 2.2.0 never got its capture adopted and the page played
     * with a free pointer and its edge turn. The port is now offered again after each of these delays (ms after the previous offer) until
     * the page answers on it ("hi" from the page's 2026-11-16 code, or the first "lock"), or the list ends (~1 minute in all).
     * A page that already holds a port takes the newer one in its place, which costs nothing while no lock is held.
     */
    private static final long[] OFFER_AGAIN_MS = {400, 800, 1200, 1600, 2000, 3000, 4000, 5000, 7000, 10000, 15000};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private WebMessagePort port;
    private float dx, dy;
    private boolean flushQueued;
    private boolean escapeHeld;
    /** the page answered on the current port */
    private boolean answered;
    /** how many times this page load has been offered the port again */
    private int offers;
    /**
     * (2.2.2, 2026-11-16) Discards the first few MotionEvents right after pointer capture is acquired.
     * Android's InputDispatcher switches from absolute to relative coordinates on capture start, often
     * emitting the previous absolute cursor position as the first delta, which caused a violent 360-degree camera spin.
     */
    private int dropInitialMoves;

    private final Choreographer.FrameCallback flushOnFrame = time -> { flushQueued = false; flush(); };
    private final Runnable offerAgain = this::offerAgain;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /** A new WebView (first start, or after its page process died): capture listener on it, no page yet. */
    void attach(WebView view) {
        main.removeCallbacks(offerAgain);
        web = view;
        port = null;
        answered = false;
        dx = dy = 0;
        dropInitialMoves = 0;
        if (!supported()) return;
        view.setOnCapturedPointerListener((v, event) -> onCaptured(event));
    }

    /** The app is closing: no offer may run after it. */
    void destroy() {
        main.removeCallbacks(offerAgain);
        closePort();
        setCursorHidden(false);
        web = null;
    }

    /** Every finished load of the game page gets a fresh channel; an older page's port is dropped. */
    void pageFinished(WebView view, String url) {
        if (!supported() || view != web || url == null || !url.startsWith(ORIGIN + "/")) return;
        offers = 0;
        offerPort(view);
    }

    /** One channel to the page: the page's end goes in a message to its window, this end listens. Then the next offer is scheduled. */
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
                answered = true; // any word from the page proves it holds this port
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
        if (web.hasPointerCapture()) return; // a held capture is never taken away from under a lock the page may be using
        offers++;
        offerPort(web);
    }

    void pageStarted() {
        main.removeCallbacks(offerAgain);
        closePort();
        answered = false;
        dx = dy = 0;
        dropInitialMoves = 0;
        setCursorHidden(false);
        if (supported() && web != null && web.hasPointerCapture()) web.releasePointerCapture();
    }

    private void closePort() {
        if (port != null) port.close();
        port = null;
        dx = dy = 0;
    }

    private void lock() {
        if (web == null) return;
        if (web.hasPointerCapture()) { send("l,1"); return; }
        dx = dy = 0;
        dropInitialMoves = 3;
        setCursorHidden(true);
        web.requestFocus();
        web.requestPointerCapture(); // the answer comes through captureChanged; no window focus = no answer, the page times out
    }

    private void unlock() {
        dx = dy = 0;
        dropInitialMoves = 0;
        setCursorHidden(false);
        if (web != null && web.hasPointerCapture()) web.releasePointerCapture();
    }

    /** From Activity.onPointerCaptureChanged. */
    void captureChanged(boolean held) {
        dx = dy = 0; // do not flush leftover menu clicks/movement into the 3D camera
        if (held) {
            dropInitialMoves = 3; // discard transition spike events from absolute to relative coordinates
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

    /** Esc while captured: release only, the page never sees the key (a desktop browser's lock does the same). */
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
                boolean relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                        || event.isFromSource(InputDevice.SOURCE_MOUSE);
                for (int i = 0; i < event.getHistorySize(); i++) {
                    accumulateDelta(getStepX(event, i, relative), getStepY(event, i, relative));
                }
                accumulateDelta(getStepX(event, -1, relative), getStepY(event, -1, relative));
                if (!flushQueued && (dx != 0 || dy != 0)) {
                    flushQueued = true;
                    Choreographer.getInstance().postFrameCallback(flushOnFrame);
                }
                return true;
            }
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                int button = domButton(event.getActionButton());
                flush(); // the movement before a click lands first
                if (button >= 0) send("b," + button + (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS ? ",1" : ",0"));
                return true; // a side button never becomes Android's Back here
            }
            case MotionEvent.ACTION_SCROLL: {
                float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) send(v > 0 ? "w,-1" : "w,1"); // Android: positive = away from the user = DOM deltaY negative
                return true;
            }
            default:
                return true;
        }
    }

    private void accumulateDelta(float stepX, float stepY) {
        // Discard anomalous single-tick spikes (e.g. pointer wrap-around, uninitialized coordinate resets)
        if (Math.abs(stepX) < MAX_STEP_DELTA) {
            dx += stepX;
        }
        if (Math.abs(stepY) < MAX_STEP_DELTA) {
            dy += stepY;
        }
    }

    private float getStepX(MotionEvent event, int historyIndex, boolean relative) {
        float x = 0;
        if (historyIndex >= 0) {
            x = event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, historyIndex);
            if (x == 0 && relative) {
                x = event.getHistoricalX(historyIndex);
            }
        } else {
            x = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
            if (x == 0 && relative) {
                x = event.getX();
            }
        }
        return x;
    }

    private float getStepY(MotionEvent event, int historyIndex, boolean relative) {
        float y = 0;
        if (historyIndex >= 0) {
            y = event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, historyIndex);
            if (y == 0 && relative) {
                y = event.getHistoricalY(historyIndex);
            }
        } else {
            y = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            if (y == 0 && relative) {
                y = event.getY();
            }
        }
        return y;
    }

    /** Whole counts like a browser's movementX; the remainder waits for the next frame. */
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
            port = null; // the page went away
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
