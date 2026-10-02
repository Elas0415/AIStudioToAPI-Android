package com.aistudio.launcher;

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
import java.util.zip.GZIPInputStream;

/**
 * Checks the UPSTREAM backend project (iBUHub/AIStudioToAPI) for a newer version
 * and, if found, downloads a prepared backend bundle and overlays it into the
 * embedded rootfs (/opt/a2a), then restarts the local service.
 *
 * This updates the Node backend only — the Android app shell is unchanged.
 *
 * Backend bundles are prebuilt artifacts (source + node_modules + ui/dist) that
 * we attach to this fork's GitHub releases, because building the Vue UI or
 * running `npm install` inside the phone is too heavy. The bundle URL template
 * is derived from the upstream tag; if no bundle exists we fall back to
 * downloading the upstream source tarball and overlaying the JS sources only
 * (works when the change does not touch the frontend build).
 */
public class UpdateChecker {

    private static final String TAG = "BackendUpdater";

    // Upstream project whose releases we track.
    private static final String UPSTREAM_RELEASES_API =
            "https://api.github.com/repos/iBUHub/AIStudioToAPI/releases/latest";
    private static final String UPSTREAM_RELEASES_PAGE =
            "https://github.com/iBUHub/AIStudioToAPI/releases/latest";
    // Our fork, which hosts prebuilt backend bundles as release assets.
    private static final String BUNDLE_BASE =
            "https://github.com/Elas0415/AIStudioToAPI-Android/releases/download/backend-";

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
        Toast.makeText(activity, "正在检查后端更新…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONObject latest = fetchJson(UPSTREAM_RELEASES_API);
                String tag = latest.optString("tag_name", "");
                String body = latest.optString("body", "");
                String htmlUrl = latest.optString("html_url", UPSTREAM_RELEASES_PAGE);
                String current = env.installedBackendVersion();

                boolean newer = isNewer(tag, current);
                handler.post(() -> showResult(newer, tag, current, body, htmlUrl));
            } catch (Exception e) {
                Log.e(TAG, "check failed", e);
                handler.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("检查更新失败")
                        .setMessage("无法访问更新服务器：\n" + e.getMessage())
                        .setPositiveButton("打开上游 Release 页",
                                (d, w) -> openBrowser(UPSTREAM_RELEASES_PAGE))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
            }
        });
    }

    private void showResult(boolean newer, String tag, String current, String body, String htmlUrl) {
        if (!newer) {
            new AlertDialog.Builder(activity)
                    .setTitle("后端已是最新")
                    .setMessage("当前后端版本：" + current + "\n上游最新版本："
                            + (tag.isEmpty() ? current : tag))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("当前后端：").append(current).append("\n")
                .append("上游最新：").append(tag).append("\n\n");
        if (body != null && !body.isEmpty()) {
            String trimmed = body.length() > 1200 ? body.substring(0, 1200) + "…" : body;
            msg.append(trimmed).append("\n\n");
        }
        msg.append("更新将在内置运行环境中覆盖后端并自动重启服务。");

        new AlertDialog.Builder(activity)
                .setTitle("发现后端新版本 " + tag)
                .setMessage(msg.toString())
                .setPositiveButton("更新", (d, w) -> doUpdate(tag))
                .setNeutralButton("查看详情", (d, w) -> openBrowser(htmlUrl))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void doUpdate(String tag) {
        Toast.makeText(activity, "正在下载后端 " + tag + "…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                // Prefer a prebuilt bundle attached to our fork's release.
                // Expected asset name: a2a-backend-<tag>.tar.gz under tag backend-<tag>.
                String bundleUrl = BUNDLE_BASE + tag + "/a2a-backend-" + tag + ".tar.gz";
                File bundle = new File(activity.getCacheDir(), "a2a-backend-" + tag + ".tar.gz");
                boolean haveBundle = false;
                try {
                    download(bundleUrl, bundle);
                    haveBundle = bundle.length() > 0;
                } catch (Exception e) {
                    Log.w(TAG, "no prebuilt bundle, will overlay source only", e);
                }

                env.installBackendUpdate(bundle, haveBundle, tag);

                handler.post(() -> {
                    if (bundle.exists()) {
                        bundle.delete();
                    }
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
                        .setMessage(e.getMessage() + "\n\n可在上游 Release 页面手动查看。")
                        .setPositiveButton("打开 Release 页",
                                (d, w) -> openBrowser(UPSTREAM_RELEASES_PAGE))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
            } finally {
                io.shutdown();
            }
        });
    }

    private void openBrowser(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // ---- helpers ----

    private static JSONObject fetchJson(String apiUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "AIStudioToAPI-Android");
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
        conn.setRequestProperty("User-Agent", "AIStudioToAPI-Android");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
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
