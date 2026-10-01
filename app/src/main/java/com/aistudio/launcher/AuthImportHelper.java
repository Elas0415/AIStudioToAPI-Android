package com.aistudio.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 方案二兜底：导入在外部（电脑/其他设备）生成的 auth-N.json。
 *
 * 用户通过 SAF 选择 JSON 文件后，App：
 *   1. 校验 JSON 结构（含 cookies 数组即可）
 *   2. 复制到 rootfs 的 /opt/a2a/configs/auth/auth-<maxIndex+1>.json
 *   3. 提示重启服务加载新账号
 */
public class AuthImportHelper {

    private static final String TAG = "AuthImport";
    private static final int REQ_PICK = 41001;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public AuthImportHelper(Activity activity) {
        this.activity = activity;
    }

    /** Shows the explain dialog and starts the SAF picker. */
    public void show() {
        new AlertDialog.Builder(activity)
                .setTitle("导入认证文件")
                .setMessage("在电脑上运行 AIStudioToAPI 的 setup-auth（或在网页控制台下载 Auth），"
                        + "把生成的 auth-N.json 传到手机后在此选择导入。\n\n"
                        + "适合开启了短信验证 / Google Prompt / Passkey 等无法自动登录的账号。")
                .setPositiveButton("选择文件", (d, w) -> pick())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        String[] mimes = new String[]{"application/json", "text/plain", "*/*"};
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        try {
            activity.startActivityForResult(
                    Intent.createChooser(i, "选择 auth JSON 文件"), REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开文件选择器: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Call from the host activity's onActivityResult. Returns true if handled. */
    public static boolean handleResult(final Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final Uri uri = data.getData();
        ExecutorService io = Executors.newSingleThreadExecutor();
        io.execute(() -> {
            try {
                String json = readText(activity, uri);
                importJson(activity, json);
            } catch (final Exception e) {
                Log.e(TAG, "import failed", e);
                handlerOf(activity).post(() -> Toast.makeText(activity,
                        "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                io.shutdown();
            }
        });
        return true;
    }

    private static Handler handlerOf(Activity a) {
        return new Handler(Looper.getMainLooper());
    }

    private static String readText(Activity activity, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception("无法读取所选文件");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static void importJson(Activity activity, String json) throws Exception {
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new Exception("不是有效的 JSON 文件");
        }
        int cookies = trimmed.indexOf("\"cookies\"");
        int origins = trimmed.indexOf("\"origins\"");
        if (cookies < 0 && origins < 0) {
            throw new Exception("文件缺少 cookies/origins 字段，可能不是 Playwright 认证文件");
        }

        RuntimeEnv env = new RuntimeEnv(activity);
        File dir = env.authDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建认证目录");
        }

        int next = 0;
        File[] files = ProotRunner.listAuthFiles(activity);
        for (File f : files) {
            String n = f.getName();
            int idx = Integer.parseInt(n.replaceAll("\\D", ""));
            if (idx >= next) {
                next = idx + 1;
            }
        }

        File dest = new File(dir, "auth-" + next + ".json");
        Files.write(dest.toPath(), trimmed.getBytes(StandardCharsets.UTF_8));

        String accountName = extractAccountName(trimmed);
        handlerOf(activity).post(() -> new AlertDialog.Builder(activity)
                .setTitle("导入成功")
                .setMessage("已保存为 " + dest.getName()
                        + (accountName != null ? "\n账号: " + accountName : "")
                        + "\n\n需要重启本地服务以加载新账号，现在重启吗？")
                .setPositiveButton("重启服务", (d, w) -> {
                    Intent svc = new Intent(activity, ServerService.class);
                    svc.setAction(ServerService.ACTION_RESTART);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        activity.startForegroundService(svc);
                    } else {
                        activity.startService(svc);
                    }
                    Toast.makeText(activity, "正在重启服务…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("稍后", null)
                .show());
    }

    private static String extractAccountName(String json) {
        try {
            int i = json.indexOf("\"accountName\"");
            if (i < 0) {
                return null;
            }
            int s = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
            int e = json.indexOf('"', s);
            if (s > 0 && e > s) {
                return json.substring(s, e);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
