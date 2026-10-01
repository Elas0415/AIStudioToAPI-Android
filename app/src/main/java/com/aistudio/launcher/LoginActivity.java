package com.aistudio.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 方案一：App 内无头自动填充登录。
 *
 * 用户在表单中填写 Google 账号凭据（邮箱/密码/TOTP/恢复邮箱），App 通过
 * proot 运行项目自带的 scripts/auth/saveAuth.js --non-interactive --headless，
 * 由脚本自动完成 Google 登录并生成 configs/auth/auth-N.json。
 *
 * 适用账号：标准邮箱+密码，可选标准 TOTP 2FA 或恢复邮箱验证。
 * 不适用：短信验证码、Google Prompt、Passkey、图形验证码（请改用导入 auth 文件）。
 */
public class LoginActivity extends AppCompatActivity {

    private static final long DEFAULT_LOGIN_TIMEOUT_MS = 300_000L;
    private static final long HARD_TIMEOUT_MS = 420_000L;

    public static final String EXTRA_IMPORT_ONLY = "import_only";

    private EditText etEmail;
    private EditText etPassword;
    private EditText etTotp;
    private EditText etRecovery;
    private LinearLayout formContainer;
    private ScrollView logContainer;
    private TextView tvLog;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Immersive.apply(this);
        setContentView(R.layout.activity_login);

        Toolbar toolbar = findViewById(R.id.loginToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etTotp = findViewById(R.id.etTotp);
        etRecovery = findViewById(R.id.etRecovery);
        formContainer = findViewById(R.id.formContainer);
        logContainer = findViewById(R.id.logContainer);
        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);

        etPassword.setImeOptions(EditorInfo.IME_ACTION_DONE);

        findViewById(R.id.btnStartLogin).setOnClickListener(v -> confirmAndStart());
        findViewById(R.id.btnVncLogin).setOnClickListener(v ->
                startActivity(new Intent(this, VncLoginActivity.class)));
        findViewById(R.id.btnImportAuth).setOnClickListener(v -> importAuth());
        findViewById(R.id.btnCancelLogin).setOnClickListener(v -> cancelLogin());

        if (ProotRunner.isLoginRunning()) {
            showRunningNotice();
        }
    }

    private void showRunningNotice() {
        switchToLogView("已有登录任务在运行…");
    }

    private void confirmAndStart() {
        final String email = etEmail.getText().toString().trim();
        final String password = etPassword.getText().toString();
        final String totp = etTotp.getText().toString().trim();
        final String recovery = etRecovery.getText().toString().trim();

        if (email.isEmpty() || !email.contains("@")) {
            etEmail.setError("请填写有效的 Google 邮箱");
            etEmail.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("请填写密码");
            etPassword.requestFocus();
            return;
        }

        StringBuilder notice = new StringBuilder();
        notice.append("账号: ").append(email).append("\n\n");
        notice.append("将在后台以无头模式自动完成 Google 登录（最长约 5 分钟）。\n");
        if (!totp.isEmpty()) {
            notice.append("已提供 TOTP 密钥，将自动填写 2FA 验证码。\n");
        } else {
            notice.append("若账号启用了 2FA，请填写 TOTP 密钥，否则可能登录失败。\n");
        }
        if (!recovery.isEmpty()) {
            notice.append("已提供恢复邮箱。\n");
        }
        notice.append("\n注意：密码仅在本次登录过程中使用，不会保存。\n")
                .append("含短信验证/Google Prompt/Passkey 的账号无法自动登录，请改用「导入认证文件」。");

        new AlertDialog.Builder(this)
                .setTitle("开始自动登录")
                .setMessage(notice.toString())
                .setPositiveButton("开始", (d, w) -> startLogin(email, password, totp, recovery))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startLogin(final String email, final String password, final String totp, final String recovery) {
        if (ProotRunner.isLoginRunning()) {
            Toast.makeText(this, "已有登录任务在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        ProotRunner.setLoginRunning(true);

        switchToLogView(null);
        appendLog("▶ 开始无头自动登录: " + email);
        appendLog("▶ 启动 Camoufox 并打开 Google 登录页…");

        final RuntimeEnv env = new RuntimeEnv(this);
        final String[] cmd = env.saveAuthCommand(email, password, totp, recovery, DEFAULT_LOGIN_TIMEOUT_MS);

        setStatus("登录进行中…", true);

        io.execute(() -> {
            try {
                java.util.Map<String, String> extraEnv = new java.util.HashMap<>();
                extraEnv.put("CAMOUFOX_EXECUTABLE_PATH", "/opt/camoufox/camoufox");
                ProotRunner.Result result = ProotRunner.run(this, env, "/opt/a2a",
                        cmd, HARD_TIMEOUT_MS, line -> handler.post(() -> onLoginLine(line)), extraEnv);
                handler.post(() -> onLoginFinished(result, email));
            } catch (final Exception e) {
                handler.post(() -> {
                    ProotRunner.setLoginRunning(false);
                    setStatus("登录执行失败: " + e.getMessage(), false);
                    appendLog("❌ " + e.getMessage());
                });
            }
        });
    }

    /** Maps saveAuth.js console output to short progress hints. */
    private void onLoginLine(String line) {
        appendLog(line);
        String compact = compact(line);
        if (compact != null) {
            setStatus(compact, true);
        }
    }

    private static String compact(String raw) {
        if (raw == null) return null;
        String s = stripAnsi(raw);
        if (s.contains("正在尝试自动填入账号") || s.contains("Attempting to auto-fill account")) {
            return "正在填入邮箱…";
        }
        if (s.contains("正在等待密码输入框") || s.contains("Waiting for password input")) {
            return "正在填入密码…";
        }
        if (s.contains("2FA 验证码") || s.contains("TOTP code")) {
            return "正在填写 2FA 验证码…";
        }
        if (s.contains("恢复邮箱") || s.contains("recovery email")) {
            return "正在处理恢复邮箱验证…";
        }
        if (s.contains("协议弹窗") || s.contains("agreement dialog")) {
            return "正在处理首次协议弹窗…";
        }
        if (s.contains("监测登录状态") || s.contains("Monitoring login status")) {
            return "等待登录完成…";
        }
        if (s.contains("检测到 AI Studio 标题") || s.contains("AI Studio title detected")) {
            return "登录成功，正在保存认证…";
        }
        if (s.contains("状态验证通过") || s.contains("State validation passed")) {
            return "认证文件校验通过";
        }
        if (s.contains("认证文件已保存") || s.contains("Authentication file saved")) {
            return "认证文件已保存";
        }
        return null;
    }

    private void onLoginFinished(ProotRunner.Result result, String email) {
        ProotRunner.setLoginRunning(false);
        appendLog("进程退出码: " + result.exitCode);

        boolean saved = result.lines.stream().anyMatch(l -> stripAnsi(l).contains("认证文件已保存")
                || stripAnsi(l).contains("Authentication file saved"));
        if (result.ok() || saved) {
            File newest = findNewestAuth();
            String name = newest != null ? newest.getName() : "auth-?.json";
            setStatus("登录成功 ✓  认证文件: " + name, false);
            appendLog("✓ 登录完成，已生成 " + name);
            askRestartServer();
        } else {
            String reason = diagnose(result.joined());
            setStatus("登录失败", false);
            appendLog("✗ " + reason);
            new AlertDialog.Builder(this)
                    .setTitle("自动登录失败")
                    .setMessage(reason + "\n\n可选择「导入认证文件」：在电脑上完成登录后导入 auth 文件。")
                    .setPositiveButton("导入认证文件", (d, w) -> importAuth())
                    .setNegativeButton("关闭", null)
                    .show();
        }
    }
    private String diagnose(String output) {
        String s = stripAnsi(output);
        if (s.contains("timed out") || s.contains("超时") || s.contains("未检测到") && s.contains("AI Studio")) {
            return "在超时时间内未完成登录。常见原因：账号开启了短信验证/Google Prompt/Passkey，或触发了 Google 人机验证。";
        }
        if (s.contains("状态验证失败") || s.contains("State validation failed")) {
            return "登录状态校验未通过（会话内容为空），请重试。";
        }
        if (s.contains("Camoufox executable not found") || s.contains("未找到 Camoufox")) {
            return "未找到 Camoufox 浏览器，请重启应用让运行环境完成初始化。";
        }
        if (s.contains("net::ERR") || s.contains("ENOTFOUND") || s.contains("ETIMEDOUT")) {
            return "网络异常：无法访问 Google，请检查网络或代理设置。";
        }
        return "登录流程异常退出，请查看日志。";
    }

    private File findNewestAuth() {
        File[] files = ProotRunner.listAuthFiles(this);
        File newest = null;
        int max = -1;
        for (File f : files) {
            String n = f.getName();
            int idx = Integer.parseInt(n.replaceAll("\\D", ""));
            if (idx > max) {
                max = idx;
                newest = f;
            }
        }
        return newest;
    }

    private void askRestartServer() {
        new AlertDialog.Builder(this)
                .setTitle("登录成功")
                .setMessage("认证文件已生成。需要重启本地服务以加载新账号，现在重启吗？")
                .setPositiveButton("重启服务", (d, w) -> {
                    Intent svc = new Intent(this, ServerService.class);
                    svc.setAction(ServerService.ACTION_RESTART);
                    startService(svc);
                    Toast.makeText(this, "正在重启服务…", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 方案二兜底：导入 auth-N.json。 */
    private void importAuth() {
        new AuthImportHelper(this).show();
    }

    private void cancelLogin() {
        if (ProotRunner.isLoginRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("取消登录")
                    .setMessage("确定终止正在进行的登录任务？")
                    .setPositiveButton("终止", (d, w) -> {
                        ProotRunner.cancelActive();
                        setStatus("已取消", false);
                        appendLog("✗ 已被用户取消");
                    })
                    .setNegativeButton("继续登录", null)
                    .show();
        } else {
            finish();
        }
    }

    private void switchToLogView(String initial) {
        formContainer.setVisibility(View.GONE);
        logContainer.setVisibility(View.VISIBLE);
        findViewById(R.id.btnStartLogin).setVisibility(View.GONE);
        findViewById(R.id.btnCancelLogin).setVisibility(View.VISIBLE);
        tvLog.setText("");
        if (initial != null) {
            setStatus(initial, true);
        }
    }

    private void appendLog(final String line) {
        handler.post(() -> {
            tvLog.append(line);
            tvLog.append("\n");
            logContainer.post(() -> logContainer.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void setStatus(final String text, final boolean busy) {
        handler.post(() -> {
            tvStatus.setText(text);
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        });
    }

    private static String stripAnsi(String s) {
        return s == null ? "" : s.replaceAll("\u001B\\[[;?0-9]*[a-zA-Z]", "");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (AuthImportHelper.handleResult(this, requestCode, resultCode, data)) {
            return;
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (ProotRunner.isLoginRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("登录进行中")
                    .setMessage("登录任务仍在后台运行，离开不会中断。确定返回？")
                    .setPositiveButton("返回", (d, w) -> finish())
                    .setNegativeButton("留在此页", null)
                    .show();
            return true;
        }
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
