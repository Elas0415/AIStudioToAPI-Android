package com.aistudio2api.launcher;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/**
 * 主界面：用内置 WebView 打开 Go 后端的管理控制台（127.0.0.1:2048）。
 *
 * 按手机浏览器的方式打开：
 *   1. 使用 WebView 自带的移动端 User-Agent；
 *   2. 不覆盖 viewport，控制台自己的 {@code width=device-width} 生效，
 *      渲染成适合手机宽度的窄布局；
 *   3. 仍保留捏合缩放，放大后可直接查看细节。
 *
 * 登录不再是一个独立的菜单功能：后端管理界面里的「浏览器登录」会驱动一个
 * *可见* 的 Camoufox（POST /api/accounts）。这里用注入的 JS 钩住该请求，
 * 一旦发起就自动切到 {@link #loginOverlay}（noVNC，显示后端 Camoufox 窗口所在
 * 的虚拟桌面），请求结束再自动切回并刷新界面。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AIStudio2API";

    private static final String VNC_URL =
            "http://127.0.0.1:" + RuntimeEnv.NOVNC_PORT + "/login.html";
    private static final int REQ_FILE_CHOOSER = 42101;

    /** Console URL; the port comes from .env because the console can change it. */
    private String baseUrl() {
        return "http://127.0.0.1:" + new RuntimeEnv(this).listenPort() + "/";
    }

    private WebView webView;
    private View loading;
    private TextView loadingText;
    private View loginOverlay;
    private androidx.appcompat.widget.Toolbar loginToolbar;
    private WebView vncWebView;
    private PinchZoomLayout vncZoom;
    private MouseJoystickView mouseJoystick;
    private View mouseLeft;
    private View mouseRight;
    /** The 「键盘」 input bar (own window, so the IME always comes up). */
    private Dialog imeBar;
    private EditText imeBarInput;
    /** Last text sent through the bar, so it can be reopened and edited. */
    private String lastImeText = "";
    /** Set by 「重连」: put the remote browser back on the sign-in page. */
    private boolean navigateToLoginOnConnect;

    /**
     * Where the remote browser is sent on 「重连」. Go through AI Studio rather
     * than a hand-built {@code accounts.google.com/v3/signin/identifier} URL:
     * Google's sign-in entry needs parameters the backend generates, and the
     * bare URL returns 400. AI Studio redirects to a valid sign-in page.
     */
    private static final String LOGIN_START_URL = "https://aistudio.google.com/";

    private ValueCallback<Uri[]> pendingFileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private BroadcastReceiver stateReceiver;

    /** True while the login window is on screen. */
    private volatile boolean loginOverlayVisible;
    /** True between "browser login" start and its HTTP response. */
    private volatile boolean loginPending;
    /** Id of the newest login attempt; older responses must not close its window. */
    private volatile int loginToken;
    /** Guards against a stale "wait for websockify" probe loading the view. */
    private int vncLoadToken;
    /** Last remote desktop size reported by the page (see {@link VncBridge}). */
    private int vncRemoteW, vncRemoteH;
    /** Last geometry reported by the login page (see VncBridge#onMetrics). */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Immersive.apply(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        webView = findViewById(R.id.webview);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);
        loginOverlay = findViewById(R.id.loginOverlay);
        loginToolbar = findViewById(R.id.loginToolbar);
        vncWebView = findViewById(R.id.vncWebView);
        vncZoom = findViewById(R.id.vncZoom);
        mouseJoystick = findViewById(R.id.mouseJoystick);
        mouseLeft = findViewById(R.id.mouseLeft);
        mouseRight = findViewById(R.id.mouseRight);
        configureLoginToolbar();
        configureMouseControls();

        // Keep the login view at the remote desktop's aspect ratio so noVNC's
        // zoom-to-fit fills it exactly. With the default geometry that ratio is
        // the view's own, so the fit is 1:1 and no letterbox is left.
        int[] vncScreen = RuntimeEnv.displaySize(this);
        setRemoteSize(vncScreen[0], vncScreen[1]);

        configureWebView();
        configureVncWebView();

        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean running = intent.getBooleanExtra(ServerService.EXTRA_RUNNING, false);
                if (running) {
                    waitForServerAndLoad();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ServerService.BROADCAST_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }

        startService(ServerService.ACTION_START);
        waitForServerAndLoad();
    }

    /**
     * Mobile-browser style WebView: the default mobile user agent, the page's
     * own viewport (width=device-width), and pinch zoom kept.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // --- mobile browser (always on: this is how the console is opened) ---
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);

        webView.setInitialScale(0);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.i("AIStudioWeb", msg.message() + " @" + msg.lineNumber());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = filePathCallback;
                try {
                    Intent chooser = params.createIntent();
                    chooser.addCategory(Intent.CATEGORY_OPENABLE);
                    chooser.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(chooser, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "file chooser failed", e);
                    pendingFileCallback = null;
                    toast("无法打开文件选择器: " + e.getMessage());
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new BlobDownloader(), "AndroidDownload");
        webView.addJavascriptInterface(new LoginBridge(), "AndroidLogin");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                fetchBlobFromJs(url);
                return;
            }
            downloadHttp(url, userAgent, contentDisposition, mimetype);
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.GONE);
                injectBlobHook(view);
                injectLoginHook(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternallyIfRemote(request.getUrl());
            }
        });
    }

    /** noVNC view: JS + DOM storage only; no zoom, to keep touch input faithful. */
    @SuppressLint("SetJavaScriptEnabled")
    private void configureVncWebView() {
        WebSettings vs = vncWebView.getSettings();
        vs.setJavaScriptEnabled(true);
        vs.setDomStorageEnabled(true);
        vs.setMediaPlaybackRequiresUserGesture(false);
        vs.setUseWideViewPort(false);
        vs.setLoadWithOverviewMode(false);
        vs.setSupportZoom(false);
        vs.setBuiltInZoomControls(false);
        vs.setUserAgentString(vs.getUserAgentString().replace("; wv", ""));

        vncWebView.setBackgroundColor(0xFF000000);
        vncWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The page reports its connection state so the app bar can show it.
        vncWebView.addJavascriptInterface(new VncBridge(), "AndroidVnc");
        vncWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternallyIfRemote(request.getUrl());
            }
        });
    }

    /**
     * Login window app bar: title, status subtitle and the overflow menu that
     * holds everything the user may need while the browser is on screen.
     */
    private void configureLoginToolbar() {
        loginToolbar.setTitle(R.string.app_name);
        loginToolbar.inflateMenu(R.menu.login_menu);
        loginToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.login_keyboard) {
                showImeBar();
                return true;
            }
            if (id == R.id.login_fit) {
                vncZoom.reset();
                return true;
            }
            if (id == R.id.login_reconnect) {
                // A reconnect must start over at the sign-in page: the remote
                // browser is a long-lived window and would otherwise come back
                // on whatever page the last attempt left open.
                navigateToLoginOnConnect = true;
                reloadVncPage();
                return true;
            }
            if (id == R.id.login_back) {
                dismissLoginOverlay();
                return true;
            }
            return false;
        });
    }

    /**
     * Wires the on-screen mouse helpers. They are separate views above the
     * noVNC WebView, so only their own bounds consume touches; the rest of the
     * page still gets normal taps, drags and pinches.
     */
    private void configureMouseControls() {
        mouseJoystick.setListener((dx, dy) -> {
            if (loginOverlayVisible && vncWebView != null) {
                vncWebView.evaluateJavascript(
                        "window.__a2apiMove&&window.__a2apiMove(" + dx + "," + dy + ")", null);
            }
        });
        bindMouseButton(mouseLeft, 1);
        bindMouseButton(mouseRight, 2);
    }

    private void bindMouseButton(View button, int rfbButton) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    sendMouseButton(rfbButton, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    sendMouseButton(rfbButton, false);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void sendMouseButton(int rfbButton, boolean down) {
        if (!loginOverlayVisible || vncWebView == null) {
            return;
        }
        vncWebView.evaluateJavascript(
                "window.__a2apiButton&&window.__a2apiButton(" + rfbButton + "," + down + ")",
                null);
    }

    /** Usability of the login window before/while the browser is on screen. */
    private void setLoginStatus(int resId) {
        loginToolbar.setSubtitle(resId == 0 ? null : getString(resId));
    }

    /**
     * Reloads the noVNC page (the 「重连」 menu item). The display bridge keeps
     * running, so this only has to rebuild the WebSocket.
     */
    private void reloadVncPage() {
        if (!loginOverlayVisible) {
            return;
        }
        setLoginStatus(R.string.login_overlay_connecting);
        vncWebView.loadUrl(VNC_URL);
    }

    /**
     * The 「键盘」 menu item: a one-line input bar pinned to the bottom of the
     * login window, with the phone's own IME above it.
     *
     * <p>Why a native {@link EditText} in its own window instead of the page's
     * hidden textarea: a canvas is not editable, so a WebView only raises the
     * IME for an editable element it owns <em>and</em> while it holds window
     * focus — a hand-off that proved unreliable here. A real EditText always
     * brings the keyboard up. What is typed is handed to the noVNC page, which
     * replays it as key events in the remote window and ends with Return, i.e.
     * it fills the focused remote field and submits it.
     */
    private void showImeBar() {
        if (!loginOverlayVisible) {
            return;
        }
        if (imeBar != null && imeBar.isShowing()) {
            imeBarInput.requestFocus();
            return;
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(4), dp(6));
        bar.setBackgroundColor(0xF20D1117);

        imeBarInput = new EditText(this);
        imeBarInput.setSingleLine(true);
        imeBarInput.setHint(R.string.login_ime_hint);
        imeBarInput.setHintTextColor(0x80FFFFFF);
        imeBarInput.setTextColor(Color.WHITE);
        imeBarInput.setTextSize(16);
        imeBarInput.setBackground(null);
        imeBarInput.setInputType(InputType.TYPE_CLASS_TEXT);
        // Start from the text the page read out of the field the user tapped
        // (see VncBridge#onFieldText), so the bar edits that field's content
        // and never carries the previous field's text into a new one.
        imeBarInput.setText(lastImeText);
        imeBarInput.setSelection(imeBarInput.getText().length());
        // No fullscreen "extract" editor: in landscape some IMEs would otherwise
        // cover the whole screen with their own text view and hide this bar.
        imeBarInput.setImeOptions(EditorInfo.IME_ACTION_SEND
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        imeBarInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (enter || actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                sendImeBarText();
                return true;
            }
            return false;
        });
        bar.addView(imeBarInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = new TextView(this);
        send.setText(R.string.login_ime_send);
        send.setTextColor(0xFF7CC4FF);
        send.setTextSize(16);
        send.setPadding(dp(16), dp(10), dp(16), dp(10));
        send.setOnClickListener(v -> sendImeBarText());
        bar.addView(send);

        imeBar = new Dialog(this);
        imeBar.requestWindowFeature(Window.FEATURE_NO_TITLE);
        imeBar.setContentView(bar);
        imeBar.setCanceledOnTouchOutside(true);
        imeBar.setOnDismissListener(d -> {
            imeBar = null;
            imeBarInput = null;
            // The dialog window took focus; hand it back so the desktop keeps
            // receiving touches (and so a later tap can type again).
            if (loginOverlayVisible) {
                vncWebView.requestFocus();
            }
        });
        Window win = imeBar.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            win.setAttributes(lp);
        }
        imeBar.show();
        imeBarInput.requestFocus();
    }

    /** Forwards the bar's text to the page, which types it and presses Return. */
    private void sendImeBarText() {
        if (imeBarInput == null || vncWebView == null) {
            return;
        }
        String text = imeBarInput.getText().toString();
        // A non-empty previous text means the remote field already holds it:
        // select-all first so this send replaces instead of appending.
        boolean replace = !lastImeText.isEmpty();
        lastImeText = text;
        vncWebView.evaluateJavascript(
                "window.__a2apiSend && window.__a2apiSend("
                        + JSONObject.quote(text) + ", " + replace + ")",
                null);
        if (imeBar != null && imeBar.isShowing()) {
            imeBar.dismiss();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Opens non-loopback links in the system browser; keeps loopback in-app. */
    private boolean openExternallyIfRemote(Uri uri) {
        String scheme = uri.getScheme();
        if ("blob".equals(scheme)) {
            fetchBlobFromJs(uri.toString());
            return true;
        }
        if ("data".equals(scheme)) {
            return true;
        }
        String host = uri.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 「浏览器登录」：后端一发起可见 Camoufox，这里就自动切到虚拟桌面
    // ------------------------------------------------------------------

    private final class LoginBridge {
        @JavascriptInterface
        public void onStarted(String url, int token) {
            Log.i(TAG, "browser login started: " + url + " token=" + token);
            // A Camoufox left over from an earlier attempt still holds the
            // account's profile lock, so the backend's new login window would
            // exit at once and the console would report 登录未完成. This call
            // runs before the login request leaves the page, i.e. before the
            // backend starts the new browser.
            new RuntimeEnv(MainActivity.this).killStaleBrowsers();
            loginToken = token;
            loginPending = true;
            handler.post(MainActivity.this::showLoginOverlay);
        }

        @JavascriptInterface
        public void onFinished(boolean ok, String detail, int token) {
            Log.i(TAG, "browser login finished ok=" + ok + " detail=" + detail
                    + " token=" + token);
            if (token != loginToken) {
                // Superseded by a newer click; that attempt owns the window now.
                return;
            }
            loginPending = false;
            handler.post(() -> {
                hideLoginOverlay(true);
                toast(getString(ok ? R.string.login_overlay_done
                        : R.string.login_overlay_failed));
            });
        }
    }

    /**
     * Callbacks from the noVNC page (login window), used only to keep the app
     * bar's status line honest: "connected" once the WebSocket is up, "ready"
     * once the remote desktop stops being a blank screen.
     */
    private final class VncBridge {
        @JavascriptInterface
        public void onState(String state) {
            handler.post(() -> {
                if (!loginOverlayVisible) {
                    return;
                }
                if ("connected".equals(state)) {
                    setLoginStatus(R.string.login_overlay_ready);
                    // A pinch from a previous attempt (or the transition while
                    // rotating) must not survive into a fresh login: fit the
                    // whole desktop first, the user can zoom afterwards.
                    vncZoom.reset();
                    if (navigateToLoginOnConnect) {
                        navigateToLoginOnConnect = false;
                        // The remote browser is a long-lived window: send it to
                        // the sign-in entry instead of leaving the old page up.
                        vncWebView.evaluateJavascript("window.__a2apiNav && window.__a2apiNav("
                                + JSONObject.quote(LOGIN_START_URL) + ")", null);
                    }
                } else if ("ready".equals(state)) {
                    // Blank desktop replaced by the browser: nothing left to say.
                    setLoginStatus(0);
                } else if ("disconnected".equals(state)) {
                    setLoginStatus(R.string.login_overlay_disconnected);
                } else {
                    setLoginStatus(R.string.login_overlay_connecting);
                }
            });
        }

        /**
         * The framebuffer size noVNC actually connected to. Using it (rather
         * than the requested geometry) keeps the view's aspect ratio correct
         * even when the display session is still running at a stale geometry,
         * e.g. after the phone was rotated without restarting the service.
         */
        @JavascriptInterface
        public void onRemoteSize(int width, int height) {
            handler.post(() -> {
                if (width <= 0 || height <= 0
                        || (width == vncRemoteW && height == vncRemoteH)) {
                    return;
                }
                vncRemoteW = width;
                vncRemoteH = height;
                setRemoteSize(width, height);
            });
        }

        /**
         * Text of the remote field the user just tapped (empty when it has
         * none). The 「键盘」 bar opens with it, so each field is edited with
         * its own content instead of the previously typed one.
         */
        @JavascriptInterface
        public void onFieldText(String text) {
            final String value = text == null ? "" : text;
            handler.post(() -> {
                lastImeText = value;
                // The clipboard echo is async: the bar may already be open.
                if (imeBarInput != null && imeBarInput.getText().length() == 0
                        && !value.isEmpty()) {
                    imeBarInput.setText(value);
                    imeBarInput.setSelection(value.length());
                }
            });
        }

        /**
         * Geometry measured inside the page: framebuffer, view, canvas, fit
         * scale and whether the canvas overflows the view. Logged and shown by
         * 「适配信息」; the numbers distinguish "the page does not fit the
         * desktop" from "the browser window is drawn outside the desktop".
         */
        @JavascriptInterface
        public void onMetrics(String json) {
            // Kept for logcat: the numbers tell a page-fit problem from a
            // remote-window problem when a screenshot is not enough.
            Log.i(TAG, "novnc metrics " + json);
        }
    }

    /** Applies the remote geometry to the zoom container and the field pair. */
    private void setRemoteSize(int width, int height) {
        vncRemoteW = width;
        vncRemoteH = height;
        vncZoom.setRemoteSize(width, height);
    }

    /**
     * Pins the noVNC view to the height the login window really has on screen.
     *
     * <p>Why: the overlay's weight-based height is only as good as the window's
     * own measurement, and that is briefly wrong while the activity rotates or
     * while the soft keyboard resizes the window (adjustResize). A view taller
     * than the visible area puts the bottom of the remote desktop off-screen —
     * exactly the "login window is still clipped" symptom.
     *
     * <p>The cap comes from the real display metrics (the same source the
     * virtual desktop geometry uses), the floor from the overlay's own height
     * so a smaller window (keyboard up) still wins.
     */
    private void pinLoginViewHeight() {
        if (vncZoom == null) {
            return;
        }
        int bar = RuntimeEnv.loginBarHeight(this);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int screenH = Math.max(1, dm.heightPixels);
        // Never let a mis-measured overlay (it can be taller than the display
        // for a frame during the rotation) push the view past the screen.
        int overlayH = loginOverlay != null && loginOverlay.getHeight() > 0
                ? Math.min(loginOverlay.getHeight(), screenH)
                : screenH;
        int h = Math.max(1, overlayH - bar);
        ViewGroup.LayoutParams lp = vncZoom.getLayoutParams();
        if (lp == null) {
            return;
        }
        boolean changed = false;
        if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            changed = true;
        }
        if (lp.height != h) {
            lp.height = h;
            changed = true;
        }
        if (!changed) {
            return;
        }
        // Drop the weight: with a fixed height the parent's leftover space (or
        // its deficit, when the overlay was measured taller than the display)
        // would otherwise be redistributed on top of the height we just pinned.
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).weight = 0f;
        }
        vncZoom.setLayoutParams(lp);
        vncZoom.requestLayout();
    }

    /**
     * Re-measures the login view after the landscape switch settles.
     *
     * <p>{@link #pinLoginViewHeight()} can run while the display metrics are
     * still portrait (the rotation is asynchronous), which leaves the zoom
     * container at portrait width and makes noVNC scale the 1440x900 desktop
     * down to a small centred rectangle. Re-run it once the new metrics are in
     * and reset the fit; this is what turns the first login from "a small
     * browser in the middle" into the full-screen browser.
     */
    private void relayoutLoginView() {
        if (!loginOverlayVisible || loginOverlay == null || vncZoom == null) {
            return;
        }
        ViewGroup.LayoutParams olp = loginOverlay.getLayoutParams();
        if (olp != null) {
            olp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            olp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            loginOverlay.setLayoutParams(olp);
        }
        pinLoginViewHeight();
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Log.i(TAG, "login view " + vncZoom.getWidth() + "x" + vncZoom.getHeight()
                + ", screen " + dm.widthPixels + "x" + dm.heightPixels);
        vncZoom.reset();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (loginOverlayVisible) {
            loginOverlay.post(this::pinLoginViewHeight);
            loginOverlay.postDelayed(this::relayoutLoginView, 350);
        }
    }

    private void showLoginOverlay() {
        if (loginOverlayVisible) {
            return;
        }
        // Do not measure/load noVNC while the window is still portrait: the
        // overlay would be 1080 wide and noVNC would fit the desktop into that
        // narrow slice. Wait for the landscape rotation to land first.
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (rotation != android.view.Surface.ROTATION_90
                && rotation != android.view.Surface.ROTATION_270) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            if (loginPending) {
                handler.postDelayed(this::showLoginOverlay, 250);
            }
            return;
        }
        loginOverlayVisible = true;
        // The browser login is a desktop-shaped page (Google's sign-in layout
        // switches to its wide two-column card), so the login window is shown
        // in landscape. configChanges already covers "orientation|screenSize",
        // so this switch does not recreate the activity: the WebViews survive
        // the rotation and the noVNC page refits itself when its view resizes.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // A fresh login window starts with no field text to carry over.
        lastImeText = "";
        mouseJoystick.cancel();
        mouseLeft.setPressed(false);
        mouseRight.setPressed(false);
        vncZoom.reset();
        // Re-sync the view's aspect with the desktop that is actually running
        // (a display-geometry change takes effect on the next backend start;
        // the page reports the real size again once it connects).
        int[] vncScreen = RuntimeEnv.displaySize(this);
        if (vncRemoteW != vncScreen[0] || vncRemoteH != vncScreen[1]) {
            setRemoteSize(vncScreen[0], vncScreen[1]);
        } else {
            vncZoom.reset();
        }
        setLoginStatus(R.string.login_overlay_connecting);
        loginOverlay.setVisibility(View.VISIBLE);
        // After the rotation this view gets a real size; pin it to the display
        // so a stale window measurement can never make it taller than the screen.
        loginOverlay.post(this::pinLoginViewHeight);
        // The rotation is asynchronous: the metrics above may still be portrait.
        // Re-run once it has settled, otherwise the 1440x900 desktop is fitted
        // to the portrait width and the browser looks tiny.
        loginOverlay.postDelayed(this::relayoutLoginView, 350);
        loginOverlay.postDelayed(this::relayoutLoginView, 900);
        // The console WebView keeps window focus while the overlay is up, and a
        // WebView that is not focused never raises the soft keyboard: the page's
        // hidden textarea becomes activeElement but the IME stays down. Hand
        // focus to the login view so tapping a text field can type.
        vncWebView.requestFocus();
        vncWebView.onResume();

        final int token = ++vncLoadToken;
        io.execute(() -> {
            boolean up = waitForPort(RuntimeEnv.NOVNC_PORT, 25_000);
            handler.post(() -> {
                if (!loginOverlayVisible || token != vncLoadToken) {
                    return;
                }
                if (up) {
                    vncWebView.loadUrl(VNC_URL);
                } else {
                    setLoginStatus(R.string.login_overlay_unavailable);
                }
            });
        });
    }

    private void hideLoginOverlay(boolean reloadConsole) {
        if (loginOverlayVisible) {
            loginOverlayVisible = false;
            vncLoadToken++;
            if (imeBar != null) {
                imeBar.dismiss();
            }
            loginOverlay.setVisibility(View.GONE);
            mouseJoystick.cancel();
            try {
                vncWebView.evaluateJavascript(
                        "window.__a2apiRelease&&window.__a2apiRelease()", null);
            } catch (Exception ignored) {
            }
            try {
                vncWebView.loadUrl("about:blank");
            } catch (Exception ignored) {
            }
            vncWebView.onPause();
            // Give focus back so the console's own inputs (and the IME) work.
            webView.requestFocus();
            // Back to the console's own (sensor-driven) orientation.
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        // Keep this outside the visibility guard: a login dismissed with
        // 「返回界面」 still finishes later and must refresh the console.
        if (reloadConsole) {
            webView.loadUrl(baseUrl());
        }
    }

    /** User chose to go back to the console while the browser login is running. */
    private void dismissLoginOverlay() {
        if (!loginOverlayVisible) {
            return;
        }
        boolean pending = loginPending;
        loginPending = false;
        // Reloading now would abort the still-pending account request, so only
        // refresh when nothing is in flight (the request finishing reloads later).
        hideLoginOverlay(!pending);
        if (pending) {
            toast(getString(R.string.login_overlay_background));
        }
    }

    private void startService(String action) {
        Intent svc = new Intent(this, ServerService.class);
        svc.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            loginPending = false;
            webView.loadUrl(baseUrl());
            return true;
        } else if (id == R.id.action_update) {
            new UpdateChecker(this).check();
            return true;
        } else if (id == R.id.action_restart) {
            restartBackendAndReload();
            return true;
        } else if (id == R.id.action_stop) {
            startService(ServerService.ACTION_STOP);
            toast("已停止本地服务");
            return true;
        } else if (id == R.id.action_shared) {
            showSharedDirDialog();
            return true;
        } else if (id == R.id.action_battery) {
            showBatteryDialog();
            return true;
        } else if (id == R.id.action_about) {
            showSelectableDialog(R.string.menu_about, getString(R.string.about_message),
                    android.R.string.ok, null, 0);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Message dialog whose body can be long-pressed and copied, so values shown
     * here (API address, logs) can be moved elsewhere.
     */
    private AlertDialog showSelectableDialog(int title, CharSequence message, int positive,
                                             DialogInterface.OnClickListener onPositive,
                                             int negative) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, onPositive);
        if (negative != 0) {
            builder.setNegativeButton(negative, null);
        }
        AlertDialog dialog = builder.show();
        TextView body = dialog.findViewById(android.R.id.message);
        if (body != null) {
            body.setTextIsSelectable(true);
        }
        return dialog;
    }

    private void showSharedDirDialog() {
        String path = new RuntimeEnv(this).sharedDirPath();
        String message = "手机/电脑路径：\n" + path
                + "\n\n容器内路径：\n/mnt/host"
                + "\n\n把文件放进手机路径后，浏览器登录窗口、容器内终端和 Web 控制台"
                + "都可以通过 /mnt/host 访问。该目录使用 App 的外部文件目录，不需要存储权限；"
                + "可通过 USB/ADB，或支持 Android/data 的文件管理器打开。";
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_shared)
                .setMessage(message)
                .setPositiveButton("复制手机路径", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("shared", path));
                    toast("路径已复制");
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void showBatteryDialog() {
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, R.string.battery_ok, Toast.LENGTH_SHORT).show();
            return;
        }
        showSelectableDialog(R.string.battery_title, getString(R.string.battery_message),
                R.string.battery_go, (d, w) -> requestBatteryExemption(),
                android.R.string.cancel);
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, R.string.battery_na, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void restartBackendAndReload() {
        loginPending = false;
        hideLoginOverlay(false);
        loadingText.setText("正在重启服务...");
        loading.setVisibility(View.VISIBLE);
        startService(ServerService.ACTION_RESTART);
        waitForRestartThenLoad();
    }

    private void waitForRestartThenLoad() {
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                return;
            }
            probeAndLoad(120_000, "服务重启超时，请再试一次或重启应用。");
        }, "restart-probe").start();
    }

    private void waitForServerAndLoad() {
        new Thread(() -> probeAndLoad(300_000,
                "服务启动超时。首次启动需解压运行环境（约 3 分钟），请稍后重试或查看日志。"),
                "port-probe").start();
    }

    private void probeAndLoad(long timeoutMs, String timeoutMsg) {
        boolean up = false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isServerUp()) {
                up = true;
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        boolean ready = up;
        handler.post(() -> {
            if (ready) {
                loading.setVisibility(View.GONE);
                webView.loadUrl(baseUrl());
            } else {
                loadingText.setText(timeoutMsg);
            }
        });
    }

    private boolean isServerUp() {
        return waitForPort(new RuntimeEnv(this).listenPort(), 600);
    }

    private static boolean waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException ignored) {
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void downloadHttp(String url, String userAgent, String contentDisposition, String mimetype) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimetype);
            req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                req.addRequestHeader("Cookie", cookie);
            }
            req.setTitle(name);
            req.setDescription(getString(R.string.app_name));
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                toast(getString(R.string.download_saved, name));
            }
        } catch (Exception e) {
            Log.w(TAG, "download failed", e);
            toast(getString(R.string.download_failed, e.getMessage()));
        }
    }

    private void fetchBlobFromJs(String blobUrl) {
        String js = "(async()=>{try{const r=await fetch(" + jsStr(blobUrl) + ");"
                + "const b=await r.blob();const fr=new FileReader();"
                + "fr.onload=()=>AndroidDownload.saveBase64(fr.result,AndroidDownload.suggestName('download'));"
                + "fr.readAsDataURL(b);}catch(e){AndroidDownload.onError(String(e));}})();";
        webView.evaluateJavascript(js, null);
    }

    private static String jsStr(String s) {
        if (s == null) {
            return "''";
        }
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    private static final String BLOB_HOOK_JS =
            "(function(){if(window.__a2apiBlobHook)return;window.__a2apiBlobHook=true;"
                    + "var origClick=HTMLAnchorElement.prototype.click;"
                    + "HTMLAnchorElement.prototype.click=function(){"
                    + "try{var href=this.href||'';"
                    + "if((href.indexOf('blob:')===0||href.indexOf('data:')===0)&&this.hasAttribute('download')){"
                    + "window.__a2apiSaveBlob(href,this.getAttribute('download')||'download');return;}"
                    + "}catch(e){}"
                    + "return origClick.apply(this,arguments);};"
                    + "document.addEventListener('click',function(ev){"
                    + "var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;"
                    + "if(!a)return;var href=a.href||'';"
                    + "if(href.indexOf('blob:')===0||href.indexOf('data:')===0){"
                    + "ev.preventDefault();ev.stopPropagation();"
                    + "window.__a2apiSaveBlob(href,a.getAttribute('download')||'download');"
                    + "}},true);"
                    + "window.__a2apiSaveBlob=function(href,name){"
                    + "try{fetch(href).then(function(r){return r.blob();}).then(function(b){"
                    + "var fr=new FileReader();"
                    + "fr.onload=function(){AndroidDownload.saveBase64(fr.result,name);};"
                    + "fr.readAsDataURL(b);}).catch(function(e){AndroidDownload.onError(String(e));});"
                    + "}catch(e){AndroidDownload.onError(String(e));}};"
                    + "})();";

    private void injectBlobHook(WebView view) {
        view.evaluateJavascript(BLOB_HOOK_JS, null);
    }

    /**
     * Watches for the management console's 「浏览器登录」 requests and switches to
     * the virtual desktop, so a click there lands straight in the login browser.
     *
     *   POST /api/accounts                  - create an account via browser login
     *   POST /api/accounts/{id}/login       - re-login an existing account
     *
     * Both block on the server until the user finishes, so the console's promise
     * settling is our "login finished" signal.
     */
    private static final String LOGIN_HOOK_JS =
            "(function(){if(window.__a2apiLoginHook)return;window.__a2apiLoginHook=true;"
                    + "function pathOf(u){var s=String(u||'');var q=s.indexOf('?');if(q>=0)s=s.slice(0,q);"
                    + "var h=s.indexOf('#');if(h>=0)s=s.slice(0,h);var p=s.indexOf('://');"
                    + "if(p>=0){var sl=s.indexOf('/',p+3);s=sl>=0?s.slice(sl):'/';}return s;}"
                    + "function isLogin(u,m){if(String(m).toUpperCase()!=='POST')return false;"
                    + "var p=pathOf(u);if(p==='/api/accounts')return true;"
                    + "if(p.slice(0,14)!=='/api/accounts/')return false;return p.slice(-6)==='/login';}"
                    + "function nextToken(){window.__a2apiLoginToken=(window.__a2apiLoginToken||0)+1;"
                    + "return window.__a2apiLoginToken;}"
                    + "function started(u,t){try{AndroidLogin.onStarted(String(u||''),t);}catch(e){}}"
                    + "function finished(ok,d,t){try{AndroidLogin.onFinished(!!ok,String(d==null?'':d),t);}catch(e){}}"
                    + "var of=window.fetch;if(of){window.fetch=function(input,init){"
                    + "var url=(typeof input==='string')?input:(input&&input.url);"
                    + "var method=(init&&init.method)||(input&&input.method)||'GET';"
                    + "var login=isLogin(url,method);var t=0;if(login){t=nextToken();started(url,t);}"
                    + "var p=of.apply(this,arguments);"
                    + "if(login){p=p.then(function(r){finished(r&&r.ok,r&&r.status,t);return r;},"
                    + "function(e){finished(false,e&&e.message,t);throw e;});}"
                    + "return p;};}"
                    + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
                    + "XMLHttpRequest.prototype.open=function(m,u){this.__a2m=m;this.__a2u=u;return oo.apply(this,arguments);};"
                    + "XMLHttpRequest.prototype.send=function(){"
                    + "var login=isLogin(this.__a2u,String(this.__a2m||'GET'));"
                    + "if(login){var self=this;var t=nextToken();started(this.__a2u,t);"
                    + "this.addEventListener('loadend',function(){finished(self.status>=200&&self.status<300,self.status,t);});}"
                    + "return os.apply(this,arguments);};"
                    + "})();";

    private void injectLoginHook(WebView view) {
        view.evaluateJavascript(LOGIN_HOOK_JS, null);
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private final class BlobDownloader {
        @JavascriptInterface
        public String suggestName(String fallback) {
            return fallback == null || fallback.isEmpty() ? "download.bin" : fallback;
        }

        @JavascriptInterface
        public void saveBase64(String dataUrl, String suggestedName) {
            try {
                if (dataUrl == null || !dataUrl.contains(",")) {
                    throw new IllegalArgumentException("empty data");
                }
                int comma = dataUrl.indexOf(',');
                String meta = dataUrl.substring(0, comma);
                String payload = dataUrl.substring(comma + 1);
                boolean base64 = meta.contains(";base64");
                byte[] bytes = base64
                        ? Base64.decode(payload, Base64.DEFAULT)
                        : payload.getBytes(StandardCharsets.UTF_8);

                String name = sanitizeName((suggestedName == null || suggestedName.isEmpty())
                        ? "download.bin" : suggestedName);

                java.io.File out;
                try {
                    java.io.File pub = new java.io.File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS), name);
                    java.io.File parent = pub.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    Files.write(pub.toPath(), bytes);
                    out = pub;
                    notifyMediaScanner(pub);
                } catch (Exception pubErr) {
                    Log.w(TAG, "public download failed, using app dir", pubErr);
                    java.io.File dir = new RuntimeEnv(MainActivity.this).downloadsDir();
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("cannot create dir");
                    }
                    out = new java.io.File(dir, name);
                    Files.write(out.toPath(), bytes);
                }
                toast(getString(R.string.download_saved, out.getName()));
            } catch (Exception e) {
                Log.w(TAG, "saveBase64 failed", e);
                toast(getString(R.string.download_failed, e.getMessage()));
            }
        }

        @JavascriptInterface
        public void onError(String message) {
            toast(getString(R.string.download_failed, message));
        }
    }

    private static String sanitizeName(String raw) {
        String name = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "download.bin";
        }
        return name;
    }

    private void notifyMediaScanner(java.io.File f) {
        try {
            android.media.MediaScannerConnection.scanFile(
                    this, new String[]{f.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && pendingFileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vncWebView != null && loginOverlayVisible) {
            vncWebView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            unregisterReceiver(stateReceiver);
        }
        if (imeBar != null) {
            imeBar.dismiss();
        }
        if (vncWebView != null) {
            vncWebView.loadUrl("about:blank");
            vncWebView.destroy();
            vncWebView = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (loginOverlayVisible) {
            dismissLoginOverlay();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Immersive.apply(this);
        }
    }
}
