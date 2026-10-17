package com.aistudio2api.launcher;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：用内置 WebView 打开 Go 后端的桌面版管理控制台（127.0.0.1:2048）。
 *
 * 「以电脑浏览器启动」等价于 Chrome 的「桌面版网站」：
 *   1. 使用桌面 Chrome 的 User-Agent；
 *   2. 开启 useWideViewPort / loadWithOverviewMode，让按桌面宽度设计的控制台
 *      自适应屏幕宽度；
 *   3. 保留捏合缩放，放大后可直接查看细节。
 * 以上均为默认行为，不再提供任何 UA / 缩放开关。
 *
 * 登录不再是一个独立的菜单功能：后端管理界面里的「浏览器登录」会驱动一个
 * *可见* 的 Camoufox（POST /api/accounts）。这里用注入的 JS 钩住该请求，
 * 一旦发起就自动切到 {@link #loginOverlay}（noVNC，显示后端 Camoufox 窗口所在
 * 的虚拟桌面），请求结束再自动切回并刷新界面。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AIStudio2API";

    private static final String BASE_URL = "http://127.0.0.1:" + RuntimeEnv.PORT + "/";
    private static final String VNC_URL =
            "http://127.0.0.1:" + RuntimeEnv.NOVNC_PORT + "/login.html";
    private static final int REQ_FILE_CHOOSER = 42101;

    /** Desktop Chrome UA so the console renders in its wide desktop layout. */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private WebView webView;
    private View loading;
    private TextView loadingText;
    private View loginOverlay;
    private WebView vncWebView;
    private PinchZoomLayout vncZoom;
    private TextView loginStatus;

    private ValueCallback<Uri[]> pendingFileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private BroadcastReceiver stateReceiver;

    /** True while the login window is on screen. */
    private volatile boolean loginOverlayVisible;
    /** True between "browser login" start and its HTTP response. */
    private volatile boolean loginPending;
    /** Guards against a stale "wait for websockify" probe loading the view. */
    private int vncLoadToken;

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
        vncWebView = findViewById(R.id.vncWebView);
        vncZoom = findViewById(R.id.vncZoom);
        loginStatus = findViewById(R.id.loginStatus);
        findViewById(R.id.loginBack).setOnClickListener(v -> dismissLoginOverlay());
        findViewById(R.id.loginFit).setOnClickListener(v -> vncZoom.reset());

        // Keep the login view at the remote desktop's aspect ratio so noVNC's
        // zoom-to-fit fills it exactly.
        int[] vncScreen = RuntimeEnv.virtualScreenSize(this);
        vncZoom.setRemoteSize(vncScreen[0], vncScreen[1]);

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
     * Desktop-site style WebView: desktop UA, wide viewport fitted to the screen
     * and pinch zoom kept, so the wide console behaves like Chrome's
     * "Request desktop site" mode.
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

        // --- desktop emulation (always on: this is what "以电脑浏览器启动" means) ---
        ws.setUserAgentString(DESKTOP_UA);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);

        webView.setInitialScale(0);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        applyDesktopViewport();

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
                injectDesktopViewport(view);
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
        vncWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternallyIfRemote(request.getUrl());
            }
        });
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
        public void onStarted(String url) {
            Log.i(TAG, "browser login started: " + url);
            loginPending = true;
            handler.post(MainActivity.this::showLoginOverlay);
        }

        @JavascriptInterface
        public void onFinished(boolean ok, String detail) {
            Log.i(TAG, "browser login finished ok=" + ok + " detail=" + detail);
            loginPending = false;
            handler.post(() -> {
                hideLoginOverlay(true);
                toast(getString(ok ? R.string.login_overlay_done
                        : R.string.login_overlay_failed));
            });
        }
    }

    private void showLoginOverlay() {
        if (loginOverlayVisible) {
            return;
        }
        loginOverlayVisible = true;
        vncZoom.reset();
        loginStatus.setText(R.string.login_overlay_preparing);
        loginOverlay.setVisibility(View.VISIBLE);
        vncWebView.onResume();

        final int token = ++vncLoadToken;
        io.execute(() -> {
            boolean up = waitForPort(RuntimeEnv.NOVNC_PORT, 25_000);
            handler.post(() -> {
                if (!loginOverlayVisible || token != vncLoadToken) {
                    return;
                }
                if (up) {
                    loginStatus.setText(R.string.login_overlay_ready);
                    vncWebView.loadUrl(VNC_URL);
                } else {
                    loginStatus.setText(R.string.login_overlay_unavailable);
                }
            });
        });
    }

    private void hideLoginOverlay(boolean reloadConsole) {
        if (loginOverlayVisible) {
            loginOverlayVisible = false;
            vncLoadToken++;
            loginOverlay.setVisibility(View.GONE);
            try {
                vncWebView.loadUrl("about:blank");
            } catch (Exception ignored) {
            }
            vncWebView.onPause();
        }
        // Keep this outside the visibility guard: a login dismissed with
        // 「返回界面」 still finishes later and must refresh the console.
        if (reloadConsole) {
            webView.reload();
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
            webView.reload();
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
        } else if (id == R.id.action_lan) {
            showLanDialog();
            return true;
        } else if (id == R.id.action_battery) {
            showBatteryDialog();
            return true;
        } else if (id == R.id.action_about) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.menu_about)
                    .setMessage(R.string.about_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLanDialog() {
        final RuntimeEnv env = new RuntimeEnv(this);
        io.execute(() -> {
            final boolean enabled = env.isLanEnabled();
            String msg = getString(R.string.lan_message);
            if (enabled) {
                String pw = env.adminPassword();
                if (pw != null && !pw.isEmpty()) {
                    msg = msg + "\n\n" + getString(R.string.lan_admin_password, pw);
                }
            }
            final String message = msg;
            handler.post(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.lan_title)
                    .setMessage(message)
                    .setPositiveButton(enabled ? R.string.lan_disable : R.string.lan_enable,
                            (d, w) -> toggleLan(env, !enabled))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        });
    }

    private void toggleLan(RuntimeEnv env, boolean enable) {
        io.execute(() -> {
            try {
                String key = env.ensureAppConfig(enable);
                env.writeEnvFile(key, enable);
                final String admin = env.adminPassword();
                handler.post(() -> {
                    if (enable) {
                        String ip = LocalNetwork.lanIp(this);
                        String msg = (ip != null ? getString(R.string.lan_enabled, ip)
                                                 : getString(R.string.lan_no_ip))
                                + "\n\n" + getString(R.string.lan_admin_password, admin);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.lan_title)
                                .setMessage(msg)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    } else {
                        Toast.makeText(this, R.string.lan_disabled, Toast.LENGTH_SHORT).show();
                    }
                    restartBackendAndReload();
                });
            } catch (Exception e) {
                Log.e(TAG, "toggle lan failed", e);
                handler.post(() -> Toast.makeText(this,
                        "设置失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showBatteryDialog() {
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, R.string.battery_ok, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_go, (d, w) -> requestBatteryExemption())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
                webView.loadUrl(BASE_URL);
            } else {
                loadingText.setText(timeoutMsg);
            }
        });
    }

    private boolean isServerUp() {
        return waitForPort(RuntimeEnv.PORT, 600);
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
                    + "function started(u){try{AndroidLogin.onStarted(String(u||''));}catch(e){}}"
                    + "function finished(ok,d){try{AndroidLogin.onFinished(!!ok,String(d==null?'':d));}catch(e){}}"
                    + "var of=window.fetch;if(of){window.fetch=function(input,init){"
                    + "var url=(typeof input==='string')?input:(input&&input.url);"
                    + "var method=(init&&init.method)||(input&&input.method)||'GET';"
                    + "var login=isLogin(url,method);if(login)started(url);"
                    + "var p=of.apply(this,arguments);"
                    + "if(login){p=p.then(function(r){finished(r&&r.ok,r&&r.status);return r;},"
                    + "function(e){finished(false,e&&e.message);throw e;});}"
                    + "return p;};}"
                    + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
                    + "XMLHttpRequest.prototype.open=function(m,u){this.__a2m=m;this.__a2u=u;return oo.apply(this,arguments);};"
                    + "XMLHttpRequest.prototype.send=function(){"
                    + "var login=isLogin(this.__a2u,String(this.__a2m||'GET'));"
                    + "if(login){var self=this;started(this.__a2u);"
                    + "this.addEventListener('loadend',function(){finished(self.status>=200&&self.status<300,self.status);});}"
                    + "return os.apply(this,arguments);};"
                    + "})();";

    private void injectLoginHook(WebView view) {
        view.evaluateJavascript(LOGIN_HOOK_JS, null);
    }

    /**
     * The console's own HTML declares {@code width=device-width}, so on a phone
     * it renders its narrow layout and panels end up clipped. Chrome's "Desktop
     * site" works by ignoring that declaration; in a WebView the equivalent is to
     * widen the layout viewport, so we rewrite the meta tag to a desktop width.
     * With {@code useWideViewPort} + {@code loadWithOverviewMode} the WebView then
     * zooms out to fit that width (nothing is cut off) and pinch-zoom works on top.
     *
     * Registered as a document-start script so the rewrite lands before the app's
     * stylesheet is evaluated, with an onPageFinished sweep as a fallback.
     */
    private void applyDesktopViewport() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return;
        }
        try {
            WebViewCompat.addDocumentStartJavaScript(
                    webView, DESKTOP_VIEWPORT_JS,
                    Collections.singleton("http://127.0.0.1:" + RuntimeEnv.PORT));
        } catch (Exception e) {
            Log.w(TAG, "document-start viewport script unavailable", e);
        }
    }

    private void injectDesktopViewport(WebView view) {
        view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null);
    }

    private static final int DESKTOP_VIEWPORT_WIDTH = 1280;

    private static final String DESKTOP_VIEWPORT_JS =
            "(function(){var W=" + DESKTOP_VIEWPORT_WIDTH + ";"
                    + "var CONTENT='width='+W+', minimum-scale=0.1, maximum-scale=5, user-scalable=yes';"
                    + "function fix(m){try{if(!m||m.tagName!=='META')return false;"
                    + "var n=(m.getAttribute('name')||'').toLowerCase();if(n!=='viewport')return false;"
                    + "if(m.getAttribute('content')!==CONTENT)m.setAttribute('content',CONTENT);return true;}"
                    + "catch(e){return false;}}"
                    + "function scan(root){try{var l=root.querySelectorAll('meta[name=viewport]');"
                    + "for(var i=0;i<l.length;i++)fix(l[i]);}catch(e){}}"
                    + "function run(){scan(document);var d=document.documentElement;"
                    + "if(!d||d.__a2apiViewportWatch)return;d.__a2apiViewportWatch=true;"
                    + "try{new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){"
                    + "var a=ms[i].addedNodes;for(var j=0;j<a.length;j++){var nd=a[j];"
                    + "if(nd&&nd.nodeType===1&&!fix(nd))scan(nd);}}})"
                    + ".observe(d,{childList:true,subtree:true});}catch(e){}}"
                    + "run();})();";

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
