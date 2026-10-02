package com.aistudio.launcher;

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
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AIStudioActivity";
    private static final int PORT = 7860;
    private static final String BASE_URL = "http://127.0.0.1:7860/";
    private static final int REQ_FILE_CHOOSER = 42001;

    private WebView webView;
    private View loading;
    private TextView loadingText;
    private ValueCallback<Uri[]> pendingFileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private BroadcastReceiver stateReceiver;

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

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.i("AIStudioWeb", msg.message() + " @" + msg.lineNumber());
                return true;
            }

            // Android WebView does nothing when a page opens <input type="file">
            // unless onShowFileChooser is implemented. The WebUI's "导入凭证"
            // (auth import / batch upload) relies on it, so we wire it up to SAF.
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = filePathCallback;
                try {
                    Intent chooser = fileChooserParams.createIntent();
                    chooser.addCategory(Intent.CATEGORY_OPENABLE);
                    // Allow selecting multiple .json/.zip auth files.
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

        // The WebUI downloads files in two ways: a plain <a download> navigation and
        // a Blob + createObjectURL anchor. Android WebView handles neither by default,
        // so "下载" buttons appeared to do nothing. We cover both:
        //   - http(s) downloads go through DownloadManager (DownloadListener);
        //   - blob: URLs are converted to base64 in JS and written via the bridge.
        webView.addJavascriptInterface(new BlobDownloader(), "AndroidDownload");
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                if (url != null && url.startsWith("blob:")) {
                    // Blob URLs never reach here reliably; handled via the JS hook.
                    fetchBlobFromJs(url);
                    return;
                }
                downloadHttp(url, userAgent, contentDisposition, mimetype);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.GONE);
                injectBlobHook(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("blob".equals(scheme)) {
                    fetchBlobFromJs(uri.toString());
                    return true;
                }
                if ("data".equals(scheme)) {
                    return true;
                }
                String host = uri.getHost();
                // WebUI, VNC (noVNC-style page served by websockify) and Google login
                // pages all run on localhost; anything else goes to the system browser.
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

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
            webView.reload();
            return true;
        } else if (id == R.id.action_update) {
            new UpdateChecker(this).check();
            return true;
        } else if (id == R.id.action_restart) {
            restartBackendAndReload();
            return true;
        } else if (id == R.id.action_api_key) {
            showApiKeyDialog();
            return true;
        } else if (id == R.id.action_custom_key) {
            showCustomKeyDialog();
            return true;
        } else if (id == R.id.action_lan) {
            showLanDialog();
            return true;
        } else if (id == R.id.action_logs) {
            showLogsDialog();
            return true;
        } else if (id == R.id.action_battery) {
            showBatteryDialog();
        } else if (id == R.id.action_login) {
            startActivity(new Intent(this, LoginActivity.class));
            return true;
        } else if (id == R.id.action_vnc_login) {
            startActivity(new Intent(this, VncLoginActivity.class));
            return true;
        } else if (id == R.id.action_console) {
            startActivity(new Intent(this, ConsoleActivity.class));
            return true;
        } else if (id == R.id.action_import_auth) {
            new AuthImportHelper(this).show();
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

    private void showApiKeyDialog() {
        io.execute(() -> {
            try {
                RuntimeEnv env = new RuntimeEnv(this);
                String key = env.ensureAppConfig(env.isLanEnabled());
                String url = "http://127.0.0.1:" + PORT + "/v1";
                String gemini = "http://127.0.0.1:" + PORT + "/v1beta";
                handler.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.menu_api_key)
                        .setMessage("API Key:\n" + key + "\n\n"
                                + "OpenAI Base URL:\n" + url + "\n\n"
                                + "Gemini Base URL:\n" + gemini + "\n\n"
                                + getString(R.string.api_key_hint))
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            } catch (IOException e) {
                handler.post(() -> Toast.makeText(this,
                        "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Lets the user set a custom API key (or reset to the default). */
    private void showCustomKeyDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.custom_key_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        try {
            String current = new RuntimeEnv(this).apiKey();
            if (current != null && !current.equals(RuntimeEnv.DEFAULT_API_KEY)) {
                input.setText(current);
                input.setSelection(current.length());
            }
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.custom_key_title)
                .setMessage(R.string.custom_key_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String key = input.getText().toString().trim();
                    boolean empty = key.isEmpty();
                    if (empty) {
                        key = RuntimeEnv.DEFAULT_API_KEY;
                    }
                    final String newKey = key;
                    final boolean restored = empty;
                    io.execute(() -> {
                        try {
                            RuntimeEnv env = new RuntimeEnv(this);
                            env.setApiKey(newKey);
                            env.writeEnvFile(newKey, env.isLanEnabled());
                            handler.post(() -> {
                                Toast.makeText(this,
                                        getString(restored ? R.string.custom_key_empty
                                                : R.string.custom_key_saved),
                                        Toast.LENGTH_SHORT).show();
                                restartBackendAndReload();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "set api key failed", e);
                            handler.post(() -> Toast.makeText(this,
                                    "设置失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLanDialog() {
        final RuntimeEnv env = new RuntimeEnv(this);
        boolean enabled = env.isLanEnabled();
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_title)
                .setMessage(R.string.lan_message)
                .setPositiveButton(enabled ? R.string.lan_disable : R.string.lan_enable,
                        (d, w) -> toggleLan(env, !enabled))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleLan(RuntimeEnv env, boolean enable) {
        io.execute(() -> {
            try {
                String key = env.ensureAppConfig(enable);
                env.writeEnvFile(key, enable);
                handler.post(() -> {
                    if (enable) {
                        String ip = LocalNetwork.lanIp(this);
                        String msg = ip != null
                                ? getString(R.string.lan_enabled, ip)
                                : getString(R.string.lan_no_ip);
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

    private void showLogsDialog() {
        io.execute(() -> {
            try {
                File log = new File(new RuntimeEnv(this).logsDir(), "a2a.log");
                String text = "暂无日志";
                if (log.exists()) {
                    String all = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
                    int len = all.length();
                    text = len > 20000 ? "…" + all.substring(len - 20000) : all;
                }
                final String content = text;
                handler.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.menu_logs)
                        .setMessage(content)
                        .setPositiveButton(R.string.logs_clear, (d, w) -> {
                            log.delete();
                            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.ok, null)
                        .show());
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this,
                        "读取日志失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
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
            probeAndLoad(90_000, "服务重启超时，请再试一次或重启应用。");
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Queues an http(s) download through the system DownloadManager. */
    private void downloadHttp(String url, String userAgent, String contentDisposition, String mimetype) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimetype);
            req.addRequestHeader("User-Agent", userAgent);
            // Carry the WebUI session cookie so isAuthenticated passes.
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

    /** Fallback: fetch a blob URL from JS and hand the bytes to the native bridge. */
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
            "(function(){if(window.__a2aBlobHook)return;window.__a2aBlobHook=true;"
            // Patch HTMLAnchorElement.prototype.click so any programmatic click on
            // an <a download> with a blob:/data: URL is captured. This is far more
            // reliable than a document-level click listener, because the WebUI
            // creates a detached anchor and calls a.click() directly.
            + "var origClick=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){"
            + "try{var href=this.href||'';"
            + "if((href.indexOf('blob:')===0||href.indexOf('data:')===0)&&this.hasAttribute('download')){"
            + "window.__a2aSaveBlob(href,this.getAttribute('download')||'download');return;}"
            + "}catch(e){}"
            + "return origClick.apply(this,arguments);};"
            // Also catch real user clicks / synthetic events on links.
            + "document.addEventListener('click',function(ev){"
            + "var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;"
            + "if(!a)return;var href=a.href||'';"
            + "if(href.indexOf('blob:')===0||href.indexOf('data:')===0){"
            + "ev.preventDefault();ev.stopPropagation();"
            + "window.__a2aSaveBlob(href,a.getAttribute('download')||'download');"
            + "}},true);"
            // Shared helper: fetch the blob and hand its base64 to the native bridge.
            + "window.__a2aSaveBlob=function(href,name){"
            + "try{fetch(href).then(function(r){return r.blob();}).then(function(b){"
            + "var fr=new FileReader();"
            + "fr.onload=function(){AndroidDownload.saveBase64(fr.result,name);};"
            + "fr.readAsDataURL(b);}).catch(function(e){AndroidDownload.onError(String(e));});"
            + "}catch(e){AndroidDownload.onError(String(e));}};"
            + "})();";

    private void injectBlobHook(WebView view) {
        view.evaluateJavascript(BLOB_HOOK_JS, null);
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    /** JS → native bridge for blob/data downloads. Only local WebUI content is loaded. */
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

                // Prefer the public Downloads directory so the user can find the file
                // with a file manager. Fall back to app-private storage if the public
                // dir is unavailable (scoped-storage edge cases).
                java.io.File out = null;
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
                toast(getString(R.string.download_saved, name));
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
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileCallback != null) {
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
            return;
        }
        if (AuthImportHelper.handleResult(this, requestCode, resultCode, data)) {
            return;
        }
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            unregisterReceiver(stateReceiver);
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
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
