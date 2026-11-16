package xyz.shootingpoint.game;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** The game page in a full-screen WebView: no address bar, and no wait for Google to verify the domain. */
public class GameActivity extends Activity {
    private static final String HOME = "https://shootingpoint.xyz/";
    private static final String APP_MARK = " PBMBApp/2.2.2"; // the login page hides its app download link on this mark
    private static final int ASK_MICROPHONE = 7;
    private static final String DISCORD_APP = "com.discord";
    /** the App Link for https://shootingpoint.xyz/ (manifest): on only while a Discord sign-in is out in the Discord app */
    private static final String DISCORD_RETURN = "xyz.shootingpoint.game.DiscordReturn";

    private FrameLayout root;
    private GameWebView web;
    private AssetCache assets;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenDone;
    private PermissionRequest waitingForMicrophone;
    private final PointerBridge pointer = new PointerBridge();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        assets = new AssetCache(getCacheDir());
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        createWebView();
        hideSystemBars();
        // Target 36 no longer calls onBackPressed on Android 16: without this, back would close the game at once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::askToLeave);
        }

        String crash = takeLastCrash();
        String discord = discordReturn(getIntent());
        if (crash == null) {
            openGame(savedInstanceState, discord);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("App error, please send a screenshot")
                .setMessage(crash)
                .setPositiveButton("OK", (dialog, which) -> openGame(savedInstanceState, discord))
                .setOnCancelListener(dialog -> openGame(savedInstanceState, discord))
                .show();
    }

    private void openGame(Bundle saved, String discord) {
        if (discord != null) web.loadUrl(discord); // the app was closed while the player was in the Discord app
        else if (saved == null || web.restoreState(saved) == null) web.loadUrl(HOME);
    }

    /** Discord came back with ?code= / ?error= for the game's page: the page in this WebView finishes the sign-in. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String discord = discordReturn(intent);
        if (discord != null) web.loadUrl(discord);
    }

    private static String discordReturn(Intent intent) {
        Uri url = intent == null ? null : intent.getData();
        if (url == null || !"https".equals(url.getScheme()) || !"shootingpoint.xyz".equals(url.getHost())) return null;
        if (url.getQueryParameter("state") == null || (url.getQueryParameter("code") == null && url.getQueryParameter("error") == null)) return null;
        return url.toString();
    }

    /**
     * (2026-11-12, owner: "Discord login that jumps straight into Discord") With the Discord app installed, its own sign-in screen
     * takes the authorize page: no password typed again. Discord then opens the page's redirect (https://shootingpoint.xyz/?code=...),
     * which comes back here through the App Link alias. The alias is on only while this one sign-in is out, so a phone's browser
     * signing in with Discord keeps its own redirect. Without the Discord app, or if it does not take the link: false, and the WebView
     * opens Discord's web page as before.
     */
    private boolean openInDiscordApp(Uri url) {
        String path = url.getPath() == null ? "" : url.getPath();
        if (!path.equals("/oauth2/authorize") && !path.equals("/api/oauth2/authorize")) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW, url).setPackage(DISCORD_APP);
        if (getPackageManager().resolveActivity(intent, 0) == null) return false;
        setDiscordReturn(true);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            setDiscordReturn(false);
            return false;
        }
    }

    private void setDiscordReturn(boolean on) {
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, DISCORD_RETURN),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            // the alias stays as it was; the sign-in then ends in the browser
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        web = new GameWebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // a phone's keyboard types into the focused view: the game page holds the focus from the start, not after a first tap
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + APP_MARK);
        assets.setUserAgent(settings.getUserAgentString());
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true); // the Cloudflare bot check runs in a third-party frame
        web.setWebViewClient(new GameClient());
        web.setWebChromeClient(new GameChrome());
        root.addView(web);
        pointer.attach(web);
        web.requestFocus();
    }

    private final class GameClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assets.open(request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri url = request.getUrl();
            String host = url.getHost() == null ? "" : url.getHost();
            boolean discord = "https".equals(url.getScheme()) && (host.equals("discord.com") || host.endsWith(".discord.com"));
            if (discord && openInDiscordApp(url)) return true;
            boolean stay = discord || ("https".equals(url.getScheme()) && host.equals("shootingpoint.xyz"));
            if (stay) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (ActivityNotFoundException e) {
                // nothing on this phone opens that link
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pointer.pageStarted();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pointer.pageFinished(view, url);
        }

        /** Without this a dead page takes the whole app with it. The same recovery a browser tab gets: load the game again. */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            root.removeView(view);
            view.destroy();
            createWebView();
            web.loadUrl(HOME);
            return true;
        }
    }

    private final class GameChrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
            fullscreenView = view;
            fullscreenDone = callback;
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            hideSystemBars();
        }

        @Override
        public void onHideCustomView() {
            if (fullscreenView == null) return;
            root.removeView(fullscreenView);
            fullscreenView = null;
            if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
            fullscreenDone = null;
            hideSystemBars();
        }

        /** Voice chat: the microphone only, and only for the game's own page. */
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            boolean ours = "shootingpoint.xyz".equals(request.getOrigin().getHost());
            boolean audioOnly = request.getResources().length == 1 && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(request.getResources()[0]);
            if (!ours || !audioOnly) { request.deny(); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(request.getResources());
                return;
            }
            waitingForMicrophone = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MICROPHONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != ASK_MICROPHONE || waitingForMicrophone == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) waitingForMicrophone.grant(waitingForMicrophone.getResources());
        else waitingForMicrophone.deny();
        waitingForMicrophone = null;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController bars = window.getInsetsController();
            if (bars != null) {
                bars.hide(WindowInsets.Type.systemBars());
                bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        setDiscordReturn(false); // back from Discord (with the code or without): browsers keep their own redirect again
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        pointer.destroy();
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return pointer.onKey(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        pointer.captureChanged(hasCapture);
    }

    @Override
    public void onBackPressed() {
        askToLeave();
    }

    /** One stray back press must not end a match. */
    private void askToLeave() {
        new AlertDialog.Builder(this)
                .setMessage("ออกจากเกม?")
                .setPositiveButton("ออก", (dialog, which) -> finish())
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private String takeLastCrash() {
        File file = new File(getFilesDir(), GameApp.CRASH_FILE);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[3000];
            int read = in.read(bytes);
            String text = read > 0 ? new String(bytes, 0, read, StandardCharsets.UTF_8) : "empty crash file";
            return text.substring(0, Math.min(1500, text.length()));
        } catch (Exception e) {
            return String.valueOf(e);
        } finally {
            file.delete();
        }
    }
}
