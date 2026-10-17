package com.aistudio2api.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检查原项目仓库（Mag1cFall/AIStudio2API）是否有新版本，并可直接下载官方
 * linux-arm64 Release 覆盖内置后端，然后重启服务。
 *
 * 之所以能直接覆盖，是因为上游为 Go 后端发布了静态链接的单文件二进制
 * （aistudio2api-vX.Y.Z-linux-arm64.tar.gz），在手机上解压替换即可，无需
 * 在设备上编译。
 */
public class UpdateChecker {

    private static final String TAG = "BackendUpdater";

    private static final String UPSTREAM_RELEASES_API =
            "https://api.github.com/repos/Mag1cFall/AIStudio2API/releases/latest";
    private static final String UPSTREAM_RELEASES_PAGE =
            "https://github.com/Mag1cFall/AIStudio2API/releases/latest";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final RuntimeEnv env;

    public UpdateChecker(Activity activity) {
        this.activity = activity;
        this.env = new RuntimeEnv(activity);
    }

    /** Entry point from the menu. */
    public void check() {
        Toast.makeText(activity, "正在检查原项目更新…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject latest = fetchJson(UPSTREAM_RELEASES_API);
                String tag = latest.optString("tag_name", "");
                String body = latest.optString("body", "");
                String htmlUrl = latest.optString("html_url", UPSTREAM_RELEASES_PAGE);
                String assetUrl = findArm64Asset(latest);
                String current = env.installedBackendVersion();

                boolean newer = isNewer(tag, current);
                handler.post(() -> showResult(newer, tag, current, body, htmlUrl, assetUrl));
            } catch (Exception e) {
                Log.e(TAG, "check failed", e);
                handler.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("检查更新失败")
                        .setMessage("无法访问更新服务器：\n" + e.getMessage())
                        .setPositiveButton("打开原项目 Release 页",
                                (d, w) -> openBrowser(UPSTREAM_RELEASES_PAGE))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
            }
        });
    }

    private void showResult(boolean newer, String tag, String current, String body,
                            String htmlUrl, String assetUrl) {
        if (!newer) {
            new AlertDialog.Builder(activity)
                    .setTitle("后端已是最新")
                    .setMessage("当前后端版本：" + current + "\n原项目最新版本："
                            + (tag.isEmpty() ? current : tag))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("当前后端：").append(current).append("\n")
                .append("原项目最新：").append(tag).append("\n\n");
        if (body != null && !body.isEmpty()) {
            String trimmed = body.length() > 1200 ? body.substring(0, 1200) + "…" : body;
            msg.append(trimmed).append("\n\n");
        }
        if (assetUrl == null) {
            msg.append("该版本未提供 linux-arm64 预编译包，请在 Release 页手动查看。");
        } else {
            msg.append("将下载官方 linux-arm64 二进制覆盖内置后端并自动重启服务。");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + tag)
                .setMessage(msg.toString())
                .setNeutralButton("查看详情", (d, w) -> openBrowser(htmlUrl))
                .setNegativeButton("稍后", null);
        if (assetUrl != null) {
            builder.setPositiveButton("更新", (d, w) -> doUpdate(tag, assetUrl));
        }
        builder.show();
    }

    private void doUpdate(String tag, String assetUrl) {
        Toast.makeText(activity, "正在下载后端 " + tag + "…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                File tarball = new File(activity.getCacheDir(), "aistudio2api-" + tag + ".tar.gz");
                download(assetUrl, tarball);
                env.installBackendUpdate(tarball, tag);

                handler.post(() -> clearWebViewCache());

                handler.post(() -> {
                    //noinspection ResultOfMethodCallIgnored
                    tarball.delete();
                    new AlertDialog.Builder(activity)
                            .setTitle("更新完成")
                            .setMessage("后端已更新到 " + tag + "。\n\n需要重启服务以生效，现在重启吗？")
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
                            .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "update failed", e);
                handler.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("更新失败")
                        .setMessage(e.getMessage() + "\n\n可在原项目 Release 页面手动查看。")
                        .setPositiveButton("打开 Release 页",
                                (d, w) -> openBrowser(UPSTREAM_RELEASES_PAGE))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
            } finally {
                io.shutdown();
            }
        });
    }

    private void clearWebViewCache() {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(activity);
            wv.clearCache(true);
            wv.destroy();
        } catch (Exception ignored) {
        }
    }

    private void openBrowser(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // ---- helpers ----

    /** Picks the {@code *-linux-arm64.tar.gz} asset from a release payload. */
    private static String findArm64Asset(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (name.contains("linux-arm64") && (name.endsWith(".tar.gz") || name.endsWith(".tgz"))) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static JSONObject fetchJson(String apiUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "AIStudio2API-Android");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return new JSONObject(sb.toString());
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "AIStudio2API-Android");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Numeric semver-ish comparison; "v" prefix stripped, non-digits treated as separators. */
    static boolean isNewer(String latestTag, String currentVersion) {
        int[] a = parseVersion(latestTag);
        int[] b = parseVersion(currentVersion);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null) {
            return new int[]{0};
        }
        String s = v.trim().replaceAll("^[vV]", "");
        if (s.isEmpty() || "unknown".equalsIgnoreCase(s)) {
            return new int[]{0};
        }
        String cleaned = s.replaceAll("[^0-9.]", ".");
        String[] parts = cleaned.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
