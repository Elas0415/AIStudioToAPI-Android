package com.aistudio.launcher;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * 命令控制台：用白名单命令驱动本地 Proot/Ubuntu 服务，等价于在 Ubuntu 中执行相应指令。
 *
 * 支持的命令：
 *   quick-start   启动 / 重启本地服务（等价于 npm run quick-start，内部直接 node main.js）
 *   setup-auth    打开 VNC 登录窗口完成 Google 授权（等价于 npm run setup-auth 的图形化版本）
 *   stop          停止本地服务
 *   status        查看服务与运行环境状态
 *   logs          查看最近的服务日志
 *   clear         清空控制台输出
 *   help          显示帮助
 *
 * 出于安全考虑，不开放任意 shell 命令。
 */
public class ConsoleActivity extends AppCompatActivity {

    private TextView output;
    private EditText input;
    private ScrollView scroll;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Immersive.apply(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117"));

        TextView title = new TextView(this);
        title.setText("命令控制台");
        title.setTextColor(Color.parseColor("#C9D1D9"));
        title.setTextSize(16);
        title.setPadding(dp(14), dp(14), dp(14), dp(6));
        root.addView(title);

        scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextColor(Color.parseColor("#C9D1D9"));
        output.setTextSize(12);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setPadding(dp(14), dp(6), dp(14), dp(14));
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(10));

        input = new EditText(this);
        input.setHint("输入命令，如 quick-start / setup-auth / help");
        input.setTextColor(Color.parseColor("#C9D1D9"));
        input.setHintTextColor(Color.parseColor("#484F58"));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = new Button(this);
        send.setText("执行");
        send.setOnClickListener(v -> submit());
        row.addView(send);

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        println("AIStudioToAPI 命令控制台");
        println("输入 help 查看可用命令。");
        println("");
    }

    private void submit() {
        String line = input.getText().toString().trim();
        if (line.isEmpty()) {
            return;
        }
        input.setText("");
        println("> " + line);
        runCommand(line.toLowerCase());
    }

    private void runCommand(String cmd) {
        switch (cmd) {
            case "help":
                println("可用命令：");
                println("  quick-start   启动/重启本地 API 服务（127.0.0.1:7860）");
                println("  setup-auth    打开 VNC 窗口完成 Google 登录并保存认证");
                println("  stop          停止本地服务");
                println("  status        查看服务与运行环境状态");
                println("  logs          查看最近的服务日志");
                println("  clear         清空本控制台输出");
                println("");
                break;

            case "quick-start":
            case "start":
                println("等价于 npm run quick-start（已构建，直接 node main.js）。");
                startService(ServerService.ACTION_RESTART);
                println("✓ 已下发启动/重启指令，服务将在后台就绪后可由主界面访问。");
                println("");
                break;

            case "setup-auth":
            case "login":
                println("打开 VNC 登录窗口（等价于 npm run setup-auth 的图形化流程）。");
                println("请确保本地服务已启动：将调用后端 /api/vnc/sessions。");
                startActivity(new Intent(this, VncLoginActivity.class));
                println("");
                break;

            case "stop":
                startService(ServerService.ACTION_STOP);
                println("✓ 已下发停止指令。");
                println("");
                break;

            case "status":
                new Thread(this::printStatus, "console-status").start();
                break;

            case "logs":
                printLogs();
                break;

            case "clear":
                output.setText("");
                break;

            default:
                println("未知命令：" + cmd + "。输入 help 查看可用命令。");
                println("");
        }
    }

    private void printStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("服务端口 127.0.0.1:7860: ");
        sb.append(portOpen() ? "运行中 ✓" : "未运行 ✗");
        sb.append('\n');

        RuntimeEnv env = new RuntimeEnv(this);
        try {
            String key = env.ensureAppConfig(env.isLanEnabled());
            sb.append("API Key: ").append(key).append('\n');
            sb.append("局域网: ").append(env.isLanEnabled() ? "已开启" : "关闭").append('\n');
            File authDir = env.authDir();
            File[] auths = ProotRunner.listAuthFiles(this);
            sb.append("已登录账号: ").append(auths.length).append(" 个");
            if (authDir != null) {
                sb.append(" (").append(authDir.getAbsolutePath()).append(')');
            }
            sb.append('\n');
        } catch (Exception e) {
            sb.append("读取配置失败: ").append(e.getMessage()).append('\n');
        }
        final String text = sb.toString();
        handler.post(() -> {
            println(text);
            println("");
        });
    }

    private void printLogs() {
        java.io.File log = new java.io.File(new RuntimeEnv(this).logsDir(), "a2a.log");
        if (!log.exists()) {
            println("暂无日志。");
            println("");
            return;
        }
        try {
            String all = new String(java.nio.file.Files.readAllBytes(log.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            int len = all.length();
            println(len > 12000 ? "…" + all.substring(len - 12000) : all);
            println("");
        } catch (Exception e) {
            println("读取日志失败: " + e.getMessage());
            println("");
        }
    }

    private boolean portOpen() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 7860), 400);
            return true;
        } catch (Exception e) {
            return false;
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

    private void println(final String text) {
        handler.post(() -> {
            output.append(text);
            output.append("\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
