package com.aistudio.launcher;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 在 App 内通过 VNC 完成 Google AI Studio 登录（等价于 WebUI 的「添加账号」）。
 *
 * 复用后端内置的 VNC 登录流程（/api/vnc/sessions → Xvfb :99 + x11vnc + websockify → Camoufox），
 * 由 App 内置的 noVNC（本地打包，不依赖 CDN）在 WebView 中渲染登录窗口。用户可手动完成
 * 短信验证码 / Google Prompt / Passkey / 人机验证等自动登录无法处理的步骤。
 *
 * 流程：
 *   1. 用 App 保存的 API Key 调 POST /login 建立会话（WebView 同源 Cookie）。
 *   2. fetch POST /api/vnc/sessions 启动 VNC 会话与 Camoufox。
 *   3. 加载 /novnc/vnc.html（后端静态目录）连接 ws://127.0.0.1:7860/vnc。
 *   4. 用户登录完成后点「保存此账号」→ fetch POST /api/vnc/auth。
 *   5. 成功后重启本地服务加载新账号。
 */
public class VncLoginActivity extends AppCompatActivity {

    private static final String BASE = "http://127.0.0.1:7860";
    private static final String NOVNC_URL = BASE + "/novnc/vnc.html?host=127.0.0.1&port=7860&path=vnc";

    private WebView webView;
    private TextView tvStatus;
    private ProgressBar progress;
    private Button btnSave;
    private Button btnCancel;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile String apiKey;
    private boolean sessionStarted = false;
    private boolean saving = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Immersive.apply(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#161B22"));
        int pad = dp(10);
        bar.setPadding(pad, pad, pad, pad);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(Color.parseColor("#C9D1D9"));
        tvStatus.setTextSize(13);
        tvStatus.setText("准备中…");
        bar.addView(tvStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        bar.addView(progress);

        btnSave = new Button(this);
        btnSave.setText("保存此账号");
        btnSave.setEnabled(false);
        btnSave.setOnClickListener(v -> saveAuth());
        bar.addView(btnSave);

        btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setOnClickListener(v -> confirmCancel());
        bar.addView(btnCancel);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String host = request.getUrl().getHost();
                return !("127.0.0.1".equals(host) || "localhost".equals(host));
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        new Thread(this::prepare, "vnc-prepare").start();
    }

    private void prepare() {
        RuntimeEnv env = new RuntimeEnv(this);
        try {
            apiKey = env.ensureAppConfig(env.isLanEnabled());
        } catch (Exception e) {
            fail("读取 API Key 失败: " + e.getMessage());
            return;
        }
        if (!waitForServer(60_000)) {
            fail("本地服务未运行，请先在主界面启动服务。");
            return;
        }
        handler.post(this::startBootstrap);
    }

    /** Loads a same-origin bootstrap page that logs in (session cookie) then starts a VNC session. */
    private void startBootstrap() {
        setStatus("正在建立登录会话…", true);
        int w = Math.max(360, getResources().getDisplayMetrics().widthPixels) / 2 * 2;
        int h = Math.max(480, getResources().getDisplayMetrics().heightPixels - dp(60)) / 2 * 2;
        webView.loadUrl(BASE + "/novnc/bootstrap.html?w=" + w + "&h=" + h);
    }

    private void loadNovnc() {
        setStatus("登录窗口已就绪，请在窗口中完成 Google 登录。", false);
        webView.loadUrl(NOVNC_URL);
    }

    private void saveAuth() {
        if (saving) {
            return;
        }
        saving = true;
        setStatus("正在保存认证…", true);
        btnSave.setEnabled(false);
        String js = "(async function(){const b=window.AndroidBridge;try{"
                + "const r=await fetch('/api/vnc/auth',{method:'POST',credentials:'include',"
                + "headers:{'Content-Type':'application/json','Accept':'application/json'},body:'{}'});"
                + "if(r.ok){const j=await r.json();b.onAuthSaved(j.accountName||'');return;}"
                + "const j=await r.json().catch(()=>({}));"
                + "b.onAuthError(j.message||('HTTP '+r.status), r.status===400);"
                + "}catch(e){b.onAuthError(String(e),false);}})();";
        webView.evaluateJavascript(js, null);
    }

    private void retrySaveWithEmail(String email) {
        setStatus("正在保存认证…", true);
        String js = "(async function(){const b=window.AndroidBridge;try{"
                + "const r=await fetch('/api/vnc/auth',{method:'POST',credentials:'include',"
                + "headers:{'Content-Type':'application/json','Accept':'application/json'},"
                + "body:JSON.stringify({accountName:" + jsStr(email) + "})});"
                + "if(r.ok){const j=await r.json();b.onAuthSaved(j.accountName||'');return;}"
                + "const j=await r.json().catch(()=>({}));b.onAuthError(j.message||('HTTP '+r.status),false);"
                + "}catch(e){b.onAuthError(String(e),false);}})();";
        webView.evaluateJavascript(js, null);
    }

    private void askForEmail(String reason) {
        saving = false;
        setStatus("无法自动识别账号，请手动填写。", false);
        final EditText input = new EditText(this);
        input.setHint("you@gmail.com");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        new AlertDialog.Builder(this)
                .setTitle("填写 Google 邮箱")
                .setMessage(reason + "\n请输入当前登录账号的邮箱，用于保存认证文件。")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String email = input.getText().toString().trim();
                    if (email.isEmpty() || !email.contains("@")) {
                        Toast.makeText(this, "请输入有效邮箱", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    retrySaveWithEmail(email);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finishSaveAttempt())
                .show();
    }

    private void finishSaveAttempt() {
        saving = false;
        btnSave.setEnabled(true);
        setStatus("登录窗口已就绪。", false);
    }

    private void onSaved(String accountName) {
        saving = false;
        setStatus("认证已保存" + (accountName.isEmpty() ? "" : "：" + accountName), false);
        new AlertDialog.Builder(this)
                .setTitle("登录成功")
                .setMessage((accountName.isEmpty() ? "认证文件已生成。" : "已保存账号：" + accountName)
                        + "\n\n需要重启本地服务以加载新账号，现在重启吗？")
                .setPositiveButton("重启服务", (d, w) -> {
                    Intent svc = new Intent(this, ServerService.class);
                    svc.setAction(ServerService.ACTION_RESTART);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                    Toast.makeText(this, "正在重启服务…", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("稍后", (d, w) -> finish())
                .show();
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("将关闭 VNC 登录窗口并清理会话，确定退出？")
                .setPositiveButton("退出", (d, w) -> {
                    closeVncSession();
                    finish();
                })
                .setNegativeButton("继续登录", null)
                .show();
    }

    private void closeVncSession() {
        try {
            webView.evaluateJavascript(
                    "if(navigator.sendBeacon){fetch('/api/vnc/sessions',"
                            + "{keepalive:true,method:'DELETE',credentials:'include'}).catch(()=>{});}",
                    null);
        } catch (Exception ignored) {
        }
    }

    private boolean waitForServer(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 7860), 500);
                return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private void setStatus(final String text, final boolean busy) {
        handler.post(() -> {
            tvStatus.setText(text);
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        });
    }

    private void fail(final String message) {
        handler.post(() -> new AlertDialog.Builder(this)
                .setTitle("无法打开登录窗口")
                .setMessage(message)
                .setPositiveButton("改用导入认证文件", (d, w) -> {
                    new AuthImportHelper(this).show();
                })
                .setNegativeButton("关闭", (d, w) -> finish())
                .show());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    /** JS → native callbacks. Only local content is loaded into this WebView. */
    private final class Bridge {
        @JavascriptInterface
        public String getApiKey() {
            return apiKey == null ? "" : apiKey;
        }

        @JavascriptInterface
        public void onSessionReady() {
            handler.post(() -> {
                sessionStarted = true;
                btnSave.setEnabled(true);
                loadNovnc();
            });
        }

        @JavascriptInterface
        public void onVncError(final String message) {
            handler.post(() -> fail("启动 VNC 会话失败：" + message));
        }

        @JavascriptInterface
        public void onAuthSaved(final String accountName) {
            handler.post(() -> onSaved(accountName == null ? "" : accountName));
        }

        @JavascriptInterface
        public void onAuthError(final String message, final boolean needsEmail) {
            handler.post(() -> {
                if (needsEmail) {
                    askForEmail("后端未能从页面自动识别邮箱。");
                } else {
                    saving = false;
                    btnSave.setEnabled(true);
                    setStatus("保存失败：" + message, false);
                    Toast.makeText(VncLoginActivity.this,
                            "保存失败：" + message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        confirmCancel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Immersive.apply(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            closeVncSession();
            webView.destroy();
        }
        super.onDestroy();
    }
}
