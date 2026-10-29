package com.aistudio2api.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「检查原项目更新」 now uses the backend's own updater
 * ({@code GET/POST /api/update}) instead of duplicating GitHub logic in Java.
 *
 * <p>Why: upstream v0.2.14 added a complete updater (release cache, proxy,
 * semver, SHA-256 digest verification, asset selection, in-place replace and
 * restart). Calling the same endpoint means the app follows exactly the same
 * rules and cannot disagree with the web console or install a corrupt/partial
 * archive. The old native GitHub downloader has been removed.
 *
 * <p>The local management service must be running (it is started with the app
 * even when {@code AUTO_START=false}); otherwise the dialog tells the user to
 * start it. The backend handles all network/proxy details.
 */
public class UpdateChecker {

    private static final String TAG = "BackendUpdater";
    private static final String UPSTREAM_RELEASES_PAGE =
            "https://github.com/Mag1cFall/AIStudio2API/releases/latest";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * check() and applyUpdate() share one background thread. It must never be shut
     * down: the update dialog outlives check(), and submitting to a terminated
     * executor throws RejectedExecutionException on the UI thread (app crash).
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "backend-updater");
        thread.setDaemon(true);
        return thread;
    });
    private final RuntimeEnv env;

    public UpdateChecker(Activity activity) {
        this.activity = activity;
        this.env = new RuntimeEnv(activity);
    }

    /** Entry point from the menu. */
    public void check() {
        Toast.makeText(activity, "正在通过本地服务检查原项目更新…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            try {
                JSONObject status = request("GET", "/api/update?refresh=1", 30_000);
                handler.post(() -> showStatus(status));
            } catch (Exception e) {
                Log.e(TAG, "local update check failed", e);
                handler.post(() -> show(new AlertDialog.Builder(activity)
                        .setTitle("检查更新失败")
                        .setMessage("无法连接本地服务的更新接口。\n\n"
                                + "请先在网页里点「启动服务」，再试一次；也可以直接打开原项目 Release 页。\n\n"
                                + e.getMessage())
                        .setPositiveButton("打开 Release 页",
                                (d, w) -> openBrowser(UPSTREAM_RELEASES_PAGE))
                        .setNegativeButton(android.R.string.cancel, null)));
            } finally {
                }
        });
    }

    private void showStatus(JSONObject status) {
        String current = status.optString("current", env.installedBackendVersion());
        String latest = status.optString("latest", current);
        boolean available = status.optBoolean("available", false);
        boolean updating = status.optBoolean("updating", false);
        String mode = status.optString("mode", "");
        String error = status.optString("error", "");
        String notes = status.optString("notes", "");
        String url = status.optString("url", UPSTREAM_RELEASES_PAGE);

        if (!error.isEmpty()) {
            show(new AlertDialog.Builder(activity)
                    .setTitle("检查更新失败")
                    .setMessage("原项目更新接口返回错误：\n" + error)
                    .setPositiveButton("打开 Release 页", (d, w) -> openBrowser(url))
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }

        if (updating) {
            show(new AlertDialog.Builder(activity)
                    .setTitle("正在更新")
                    .setMessage("后端正在下载/校验新版本，请稍后刷新页面。")
                    .setPositiveButton(android.R.string.ok, null));
            return;
        }

        if (!available) {
            show(new AlertDialog.Builder(activity)
                    .setTitle("后端已是最新")
                    .setMessage("当前后端版本：" + current + "\n原项目最新版本：" + latest)
                    .setPositiveButton(android.R.string.ok, null));
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("当前后端：").append(current).append("\n")
                .append("原项目最新：").append(latest).append("\n");
        if (!mode.isEmpty()) {
            msg.append("更新方式：").append(mode).append("\n");
        }
        msg.append("\n");
        if (!notes.isEmpty()) {
            String trimmed = notes.length() > 1200 ? notes.substring(0, 1200) + "…" : notes;
            msg.append(trimmed).append("\n\n");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + latest)
                .setNeutralButton("查看详情", (d, w) -> openBrowser(url))
                .setNegativeButton("稍后", null);

        if ("binary".equals(mode)) {
            msg.append("将调用原项目更新器：下载、校验 SHA-256 后原地替换后端，并自动重启。");
            builder.setPositiveButton("更新", (d, w) -> applyUpdate(latest));
        } else {
            msg.append("当前运行方式（").append(mode.isEmpty() ? "未知" : mode)
                    .append("）不支持原地更新，请在 Release 页手动处理。");
            builder.setPositiveButton("打开 Release 页", (d, w) -> openBrowser(url));
        }
        builder.setMessage(msg.toString());
        show(builder);
    }

    private void applyUpdate(String latest) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Toast.makeText(activity, "正在调用原项目更新器…", Toast.LENGTH_SHORT).show();
        final String previous = env.installedBackendVersion();
        IO.execute(() -> {
            JSONObject status = null;
            try {
                // 后端同步下载并替换二进制，替换后它会重启自身，连接中断属正常。
                status = request("POST", "/api/update", 600_000);
            } catch (Exception e) {
                Log.w(TAG, "apply update interrupted (backend may be restarting)", e);
            }
            String error = waitForUpdateFinished(status, previous);
            String installed = status != null
                    ? status.optString("current", latest).trim()
                    : latest;
            if (error == null) {
                try {
                    env.setInstalledBackendVersion(installed);
                } catch (IOException e) {
                    Log.w(TAG, "cannot record backend version", e);
                }
            }
            final String result = error;
            final String version = installed;
            handler.post(() -> {
                if (result == null) {
                    show(new AlertDialog.Builder(activity)
                            .setTitle("更新完成")
                            .setMessage("后端已更新到 " + version + "。\n\n"
                                    + "正在重启服务以加载新二进制，界面会在服务就绪后自动刷新。")
                            .setPositiveButton(android.R.string.ok, null));
                    restartService();
                } else {
                    show(new AlertDialog.Builder(activity)
                            .setTitle("更新未完成")
                            .setMessage(result + "\n\n"
                                    + "可以稍后重试，或打开 Release 页手动更新。")
                            .setPositiveButton("打开 Release 页",
                                    (d, w) -> openBrowser(UPSTREAM_RELEASES_PAGE))
                            .setNegativeButton(android.R.string.cancel, null));
                }
            });
        });
    }

    /**
     * 等更新落地。
     *
     * <p>POST 是同步的：正常返回即已替换完成。若连接被后端的自我重启打断，
     * 轮询 GET /api/update，直到 updating 变回 false 且版本号已经变化；
     * 期间连不上按“正在重启”处理。
     */
    private String waitForUpdateFinished(JSONObject post, String previous) {
        if (post != null) {
            String error = post.optString("error", "").trim();
            return error.isEmpty() ? null : error;
        }
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "已取消。";
            }
            JSONObject status;
            try {
                status = request("GET", "/api/update", 20_000);
            } catch (Exception e) {
                continue;
            }
            if (status.optBoolean("updating", false)) {
                continue;
            }
            String error = status.optString("error", "").trim();
            if (!error.isEmpty()) {
                return error;
            }
            String version = status.optString("current", "").trim();
            if (!version.isEmpty() && !version.equals(previous)) {
                return null;
            }
        }
        return "等待更新完成超时（10 分钟）。如果服务已经恢复，可以手动重启服务再试。";
    }

    private void restartService() {
        try {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).restartAfterUpdate();
            } else {
                activity.startService(new Intent(activity, ServerService.class)
                        .setAction(ServerService.ACTION_RESTART));
            }
        } catch (Exception e) {
            Log.e(TAG, "restart after update failed", e);
        }
    }

    /** Calls the local backend; the backend owns GitHub/proxy/digest handling. */
    private JSONObject request(String method, String path, int readTimeoutMs) throws Exception {
        String key = env.apiKey();
        URL url = new URL("http://127.0.0.1:" + env.listenPort() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "AIStudio2API-Android");
        if (key != null && !key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(readTimeoutMs);
        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()))) {
            if (r != null) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + (sb.length() > 0 ? ": " + sb : ""));
        }
        return new JSONObject(sb.toString());
    }

    /** Shows the dialog with a message that can be long-pressed and copied. */
    private static void show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
        TextView body = dialog.findViewById(android.R.id.message);
        if (body != null) {
            body.setTextIsSelectable(true);
        }
    }

    private void openBrowser(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
