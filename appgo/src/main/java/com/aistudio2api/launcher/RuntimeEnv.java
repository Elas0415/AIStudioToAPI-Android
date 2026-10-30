package com.aistudio2api.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Prepares and describes the embedded PRoot + Ubuntu 24.04 ARM64 runtime that
 * hosts the Go backend {@code Mag1cFall/AIStudio2API} plus Camoufox.
 *
 * Layout under filesDir/runtime/:
 *   lib/libtalloc.so.2, lib/libandroid-shmem.so  - proot deps
 *   tmp/                                         - proot temp
 *   dev/                                         - app-private /dev (see devBinds)
 *   rootfs/                                      - Ubuntu 24.04 arm64
 *     opt/aistudio2api/  - the Go backend (binary + .env + auth/ + runtime/)
 *     opt/camoufox/      - Camoufox arm64 browser (from the base rootfs)
 *     opt/novnc/         - noVNC client served by websockify
 *     usr/bin/Xvnc       - TigerVNC X server + RFB server (from assets/xvnc)
 *     usr/bin/websockify - RFB -> WebSocket bridge (from the base rootfs)
 *
 * The virtual desktop exists because the backend's account creation (the 「浏览器
 * 登录」 button in its management UI) drives a *visible* Camoufox browser. The app
 * keeps Xvnc :99 alive next to the service and exposes it as noVNC on
 * 127.0.0.1:6080, which the WebView shows while such a request is in flight.
 */
final class RuntimeEnv {

    private static final String TAG = "RuntimeEnv";

    /**
     * Bumped whenever the extraction/deploy steps change.
     * v4: the bundled rootfs is back to the stock upstream backend (v0.2.14);
     * the EXTRA_MODELS experiment was reverted.
     */
    static final String ROOT_MARKER = ".extracted-go-v4";
    /**
     * Bumped whenever anything under {@code assets/novnc} changes, otherwise an
     * existing install keeps the previously deployed copy (the deploy is
     * marker-gated). v2: login.html stopped overriding noVNC's canvas sizing.
     * v4: login.html re-fits (and, if needed, clamps) the canvas whenever the
     * view resizes, so a stale view size can no longer crop the desktop.
     * v5: tapping a text field (I-beam cursor) raises the IME, taps elsewhere
     * put it away; the manual 「显示键盘」/「输入文本」 menu entries are gone.
     */
    static final String NOVNC_MARKER = ".novnc-go-v11";

    static final int PORT = 2048;
    static final String LOOPBACK = "127.0.0.1";

    /** Directory (inside the rootfs) that holds the Go backend. */
    static final String BACKEND_DIR = "/opt/aistudio2api";
    static final String CAMOUFOX_PATH = "/opt/camoufox/camoufox-bin";
    static final String NOVNC_DIR = "/opt/novnc";

    static final String DISPLAY = ":99";
    static final int VNC_PORT = 5900;
    static final int NOVNC_PORT = 6080;

    /**
     * Geometry of the login view itself, in physical pixels: the phone's own
     * display area, landscape, minus the login window's app bar
     * ({@code @dimen/login_bar_height}, the same dimension the overlay layout
     * uses). This is the 「显示分辨率」→「follow the phone」 choice: the remote
     * screen then has the same pixel grid as the view it is shown in, so
     * noVNC's scale-to-fit is 1:1 — no letterbox and the browser rasterises at
     * native resolution.
     *
     * <p>Landscape because the login window is shown in landscape (see
     * MainActivity#showLoginOverlay): the long edge of the display becomes the
     * desktop's width, the short edge its height.
     *
     * <p>Note this is decided when the backend starts (the geometry is Xvnc's
     * {@code -geometry}), so rotating the phone mid-session keeps the old raster
     * until the service restarts — the same trade-off the reference project has,
     * because its session is created per login attempt.
     */
    static int[] virtualScreenSize(Context ctx) {
        int[] fallback = {DEFAULT_DISPLAY_W, DEFAULT_DISPLAY_H};
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return fallback;
            }
            // Real metrics = the whole display, which is the area the app owns
            // while it runs immersive (MainActivity hides the system bars).
            wm.getDefaultDisplay().getRealMetrics(dm);
            int longEdge = Math.max(1, Math.max(dm.widthPixels, dm.heightPixels));
            int shortEdge = Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
            int w = longEdge;
            int h = shortEdge - loginBarHeight(ctx);
            // Clamp instead of bailing out: a slightly wrong geometry only costs
            // a hair of letterboxing, a fixed fallback would cost the aspect.
            w = Math.min(MAX_DISPLAY, Math.max(MIN_DISPLAY, w));
            h = Math.min(MAX_DISPLAY, Math.max(MIN_DISPLAY, h));
            return new int[]{w, h};
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Height of the login window's app bar in pixels. Deliberately a dimension
     * resource rather than {@code ?attr/actionBarSize} so the value used to size
     * the virtual desktop is byte-for-byte the value the overlay's Toolbar is
     * laid out with.
     */
    static int loginBarHeight(Context ctx) {
        try {
            return ctx.getResources().getDimensionPixelSize(R.dimen.login_bar_height);
        } catch (Exception e) {
            return Math.round(56 * ctx.getResources().getDisplayMetrics().density);
        }
    }

    // ------------------------------------------------------------------
    // Display resolution (Termux:X11 style)
    // ------------------------------------------------------------------

    /*
     * The virtual desktop the login window is drawn on exists only as Xvnc's
     * -geometry, so changing it means restarting the display session.
     * The choice lives in SharedPreferences, not in the JSON app config, because
     * writeAppConfig() rewrites that file from scratch on every backend start.
     */

    private static final String PREFS = "a2api_display";
    private static final String PREF_W = "display_w";
    private static final String PREF_H = "display_h";

    /** Sane bounds for a hand-typed geometry. */
    static final int MIN_DISPLAY = 320;
    static final int MAX_DISPLAY = 4096;

    private static SharedPreferences displayPrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The user-picked geometry, or null while following the phone's screen. */
    static int[] customDisplaySize(Context ctx) {
        try {
            SharedPreferences p = displayPrefs(ctx);
            int w = p.getInt(PREF_W, 0);
            int h = p.getInt(PREF_H, 0);
            if (w >= MIN_DISPLAY && w <= MAX_DISPLAY
                    && h >= MIN_DISPLAY && h <= MAX_DISPLAY) {
                return new int[]{w, h};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Pass null to go back to following the phone. */
    static void setCustomDisplaySize(Context ctx, Integer w, Integer h) {
        SharedPreferences.Editor e = displayPrefs(ctx).edit();
        if (w == null || h == null) {
            e.remove(PREF_W).remove(PREF_H);
        } else {
            e.putInt(PREF_W, w).putInt(PREF_H, h);
        }
        e.apply();
    }

    /**
     * Geometry of the virtual desktop: always the phone's own landscape area
     * (minus the login app bar).
     *
     * <p>The old 1280x720 default made noVNC letterbox the desktop at the sides
     * of a 2400x1080 phone, which looked like "the browser only uses a small
     * part of the screen" until the user pinch-zoomed. Matching the view's
     * pixel grid makes the remote desktop and noVNC 1:1: the browser window
     * (maximised by tinywm) fills the login view with no zoom or black bars.
     */
    static int[] displaySize(Context ctx) {
        return virtualScreenSize(ctx);
    }

    /**
     * Default geometry of the virtual desktop: 1280×720.
     *
     * <p>Landscape, because the login window is shown in landscape
     * (MainActivity#showLoginOverlay switches the activity orientation). The
     * remote page therefore gets the viewport of an ordinary desktop browser,
     * which is what Google's sign-in page expects: in portrait it switches to
     * its tall single-column layout, and its two-column card (the one the
     * comments in {@code login.html} refer to) is only used from ~1000 px up.
     *
     * <p>Still a *setting*: 「显示分辨率」 offers this size, a few standard ones
     * and 「follow the phone」 (the phone's own landscape area, which makes the
     * login view 1:1 with no letterbox).
     */
    static final int DEFAULT_DISPLAY_W = 1280;
    static final int DEFAULT_DISPLAY_H = 720;

    static final String DEFAULT_API_KEY = "123456";

    private final Context ctx;
    private final File base;
    private final File rootfs;
    private final File backendDir;

    RuntimeEnv(Context ctx) {
        this.ctx = ctx;
        this.base = new File(ctx.getFilesDir(), "runtime");
        this.rootfs = new File(base, "rootfs");
        this.backendDir = new File(rootfs, "opt/aistudio2api");
    }

    File backendDir() {
        return backendDir;
    }

    File backendBinary() {
        return new File(backendDir, "aistudio2api");
    }

    File authDir() {
        return new File(backendDir, "auth");
    }

    File envFile() {
        return new File(backendDir, ".env");
    }

    File versionFile() {
        return new File(backendDir, "VERSION");
    }

    String rootfsPath() {
        return rootfs.getAbsolutePath();
    }

    File prootDir() {
        return base;
    }

    File logsDir() {
        File dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    File downloadsDir() {
        File dir = new File(ctx.getFilesDir(), "downloads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    File appConfigFile() {
        return new File(ctx.getFilesDir(), "config/app.json");
    }

    // ------------------------------------------------------------------
    // Backend version (tracked by us, since the release binary does not
    // expose a stable --version in a machine-readable form).
    // ------------------------------------------------------------------

    String installedBackendVersion() {
        try {
            File f = versionFile();
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    /**
     * 后端二进制的最后修改时间。在线更新会原地替换
     * {@code /opt/aistudio2api/aistudio2api}，用它判断替换是否已完成——
     * 更新器完成替换后会 stop 自身，端口随之消失，不能再靠 HTTP 接口确认。
     */
    long backendBinaryLastModified() {
        return new File(backendDir, "aistudio2api").lastModified();
    }

    void setInstalledBackendVersion(String version) throws IOException {
        Files.write(versionFile().toPath(),
                (version == null ? "unknown" : version.trim()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Replaces the Go backend binary with the one inside an upstream release
     * tarball ({@code aistudio2api-vX.Y.Z-linux-arm64.tar.gz}).
     *
     * The tarball contains a single top-level directory holding the static
     * {@code aistudio2api} executable; we locate it by name and overwrite
     * /opt/aistudio2api/aistudio2api in place, then record the version.
     */
    void installBackendUpdate(File tarball, String tag) throws IOException {
        String tar = findTar();
        if (tar == null) {
            throw new IOException("no tar available on device");
        }
        File tmp = new File(base, "update-tmp");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create update temp dir");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(tar, "xzf", tarball.getAbsolutePath(), "-C",
                    tmp.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("解压失败 (tar=" + code + "): " + out.toString().trim());
            }

            File found = findFileNamed(tmp, "aistudio2api");
            if (found == null) {
                throw new IOException("压缩包中未找到 aistudio2api 可执行文件");
            }

            // Overwrite in place: write to a sibling then rename so a crash cannot
            // leave a truncated binary behind.
            File target = backendBinary();
            File staging = new File(backendDir, "aistudio2api.new");
            Files.copy(found.toPath(), staging.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            staging.setExecutable(true, false);
            Files.move(staging.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            target.setExecutable(true, false);

            setInstalledBackendVersion(tag);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解压被中断");
        } finally {
            deleteRecursive(tmp);
        }
    }

    private static File findFileNamed(File dir, String name) {
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        // Prefer shallow matches (the release layout is <dir>/<name>).
        for (File c : children) {
            if (c.isFile() && c.getName().equals(name)) {
                return c;
            }
        }
        for (File c : children) {
            if (c.isDirectory()) {
                File found = findFileNamed(c, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // App config (apiKey), persisted as JSON.
    // ------------------------------------------------------------------

    /**
     * 当前生效的 API key。
     *
     * <p>以 .env 为准：控制台保存的 PROXY_API_KEY 必须能跨 App 重启存活。
     * 只有 .env 里根本没有这个键时（首次启动前）才回落到 App 自己的配置。
     */
    String apiKey() {
        Map<String, String> values = readEnvFile();
        if (values.containsKey("PROXY_API_KEY")) {
            String value = values.get("PROXY_API_KEY");
            return value == null ? "" : value.trim();
        }
        return appConfig("apiKey");
    }

    private String appConfig(String key) {
        try {
            File f = appConfigFile();
            if (f.exists()) {
                JSONObject o = new JSONObject(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                return o.optString(key, "");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Reads or creates the persisted app config and returns the API key. */
    synchronized String ensureAppConfig() throws IOException {
        String key = apiKey();
        if (key == null || key.isEmpty()) {
            key = DEFAULT_API_KEY;
        }
        writeAppConfig(key);
        return key;
    }

    private void writeAppConfig(String key) throws IOException {
        File f = appConfigFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create config dir");
        }
        try {
            JSONObject o = new JSONObject();
            o.put("apiKey", key == null ? "" : key);
            Files.write(f.toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("encode app config: " + e.getMessage(), e);
        }
    }

    /**
     * Port the backend is actually configured to listen on.
     *
     * <p>The console's settings page writes {@code LISTEN_ADDR} to .env, so the
     * app must follow that value instead of assuming {@link #PORT}. The port is
     * only changed by the console; the LAN toggle changes the host part.
     */
    int listenPort() {
        return parseListenPort(readEnvFile().get("LISTEN_ADDR"), PORT);
    }

    static int parseListenPort(String address, int fallback) {
        if (address == null) {
            return fallback;
        }
        String value = address.trim();
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1).trim();
        }
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Runtime preparation
    // ------------------------------------------------------------------

    /**
     * Moves the state that must survive a rootfs re-extraction out of the old
     * tree. Auth contains the Google logins (and their browser caches), .env
     * holds the console settings, and runtime holds the request DB and logs.
     */
    private void preserveState(File rootfs, File state) {
        // A previous upgrade was interrupted after the state was moved aside;
        // keep that copy instead of replacing it with the (possibly partial)
        // rootfs that is still on disk.
        if (new File(state, "auth").exists()
                || new File(state, ".env").exists()
                || new File(state, "runtime").exists()) {
            Log.i(TAG, "reusing rootfs state preserved by an interrupted upgrade");
            return;
        }
        deleteRecursive(state);
        File backend = new File(rootfs, "opt/aistudio2api");
        if (!backend.isDirectory()) {
            return;
        }
        if (!state.mkdirs() && !state.isDirectory()) {
            Log.w(TAG, "cannot create rootfs upgrade state dir");
            return;
        }
        movePath(new File(backend, "auth"), new File(state, "auth"));
        movePath(new File(backend, ".env"), new File(state, ".env"));
        movePath(new File(backend, "runtime"), new File(state, "runtime"));
    }

    private void restoreState(File rootfs, File state) {
        File backend = new File(rootfs, "opt/aistudio2api");
        if (!backend.isDirectory() && !backend.mkdirs()) {
            Log.w(TAG, "cannot create backend dir while restoring state");
            return;
        }
        movePath(new File(state, "auth"), new File(backend, "auth"));
        movePath(new File(state, ".env"), new File(backend, ".env"));
        movePath(new File(state, "runtime"), new File(backend, "runtime"));
        deleteRecursive(state);
    }

    /** Deletes the target (the fresh rootfs has empty placeholders) and renames. */
    private void movePath(File from, File to) {
        if (!from.exists()) {
            return;
        }
        try {
            if (to.exists()) {
                deleteRecursive(to);
            }
            if (from.renameTo(to)) {
                return;
            }
            Log.w(TAG, "cannot move " + from + " to " + to);
        } catch (Exception e) {
            Log.w(TAG, "cannot move " + from + " to " + to, e);
        }
    }

    /** Extracts everything and builds the rootfs on first run. */
    void ensureReady() throws IOException {
        File marker = new File(base, ROOT_MARKER);
        File libDir = new File(base, "lib");
        File tmpDir = new File(base, "tmp");

        if (!base.exists() && !base.mkdirs()) {
            throw new IOException("cannot create " + base);
        }
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw new IOException("cannot create tmp dir");
        }
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw new IOException("cannot create lib dir");
        }
        logsDir();

        AssetManager am = ctx.getAssets();

        if (!marker.exists()) {
            Log.i(TAG, "first run: extracting runtime (this takes a few minutes)");
            copyAsset(am, "libtalloc.so.2", new File(libDir, "libtalloc.so.2"));
            copyAsset(am, "libandroid-shmem.so", new File(libDir, "libandroid-shmem.so"));

            File tarZst = new File(base, "rootfs.tar.zst");
            copyAsset(am, "rootfs.tar.zst", tarZst);
            // Account logins, the console's settings and request history live
            // inside the rootfs; keep them across a rootfs upgrade.
            File upgradeState = new File(base, "upgrade-state");
            preserveState(rootfs, upgradeState);
            if (rootfs.exists()) {
                deleteRecursive(rootfs);
            }
            if (!rootfs.mkdirs()) {
                throw new IOException("cannot create rootfs dir");
            }
            extractTar(tarZst, rootfs);
            tarZst.delete();
            restoreState(rootfs, upgradeState);

            makeLibsExecutable(new File(rootfs, "lib"));
            makeLibsExecutable(new File(rootfs, "usr/lib"));
            makeLibsExecutable(new File(rootfs, "usr/local/bin"));

            backendDir.mkdirs();
            authDir().mkdirs();
            new File(backendDir, "runtime").mkdirs();

            if (!marker.createNewFile()) {
                Log.w(TAG, "could not create marker");
            }
        }

        authDir().mkdirs();
        new File(backendDir, "runtime").mkdirs();
        makeExecutable(backendBinary());
        makeExecutable(new File(rootfs, "opt/camoufox/camoufox-bin"));
        makeExecutable(new File(rootfs, "opt/camoufox/camoufox"));

        // Refresh launcher + VNC scripts every start so upgrades apply.
        deployScripts(am);
        deployCaCertificates(am);
        ensureCamoufoxFontconfig();
        ensureCamoufoxSandboxWarningSuppressed();
        ensureSharedDir();
        deployNovnc(am);
        deployDisplayStack(am);
        ensureDevDir();
        writeResolvConf();
        writeHostsFile();

        // The backend reads .env from its working directory; make sure one exists
        // even when a maintenance command (setup --storage-state) runs before the
        // service has ever been started.
        if (!envFile().exists()) {
            writeEnvFile(ensureAppConfig());
        }
    }

    private void deployScripts(AssetManager am) throws IOException {
        copyAsset(am, "scripts/start-aistudio2api.sh",
                new File(backendDir, "start-aistudio2api.sh"));
        makeExecutable(new File(backendDir, "start-aistudio2api.sh"));
        copyAsset(am, "scripts/vnc-serve.sh", new File(backendDir, "vnc-serve.sh"));
        makeExecutable(new File(backendDir, "vnc-serve.sh"));
        copyAsset(am, "scripts/kill-browsers.sh", new File(backendDir, "kill-browsers.sh"));
        makeExecutable(new File(backendDir, "kill-browsers.sh"));
    }

    /**
     * The Go backend reads Camoufox's font profile from
     * {@code /opt/camoufox/fontconfig/<os>/fonts.conf}, but the Camoufox build
     * in our rootfs ships that directory as {@code fontconfigs} (plural). The
     * mismatch aborts every browser launch with "读取 Camoufox Windows 字体配置:
     * ... no such file or directory", which kills account warmup and the
     * 「浏览器登录」 flow. Alias the two names.
     */
    private void ensureCamoufoxFontconfig() {
        File link = new File(rootfs, "opt/camoufox/fontconfig");
        File target = new File(rootfs, "opt/camoufox/fontconfigs");
        if (link.exists() || !target.isDirectory()) {
            return;
        }
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath().getFileName());
        } catch (Exception e) {
            Log.w(TAG, "cannot create fontconfig alias: " + e.getMessage());
        }
    }

    /**
     * Camoufox/Firefox 146+ 在 MOZ_DISABLE_CONTENT_SANDBOX=1 时会挂一条常驻通知：
     * “The security sandbox is disabled…”。PRoot 提供不了内容进程所需的命名空间，
     * 这个环境变量必须保留，所以只能让通知不出现：
     *
     * <ol>
     *   <li>AutoConfig（{@code /opt/camoufox/camoufox.cfg}）里把
     *       {@code SandboxUtils} 的两个告警函数改成空实现，并在窗口启动后兜底移除
     *       残留的通知条；这是根因层的修补，通知根本不会被创建。</li>
     *   <li>camoufox-bin 外层包装器在每个登录 profile 里写 userChrome.css 与
     *       user.js，隐藏所有通知容器；AutoConfig 失效时仍有一层兜底。</li>
     * </ol>
     *
     * <p>包装器带版本号：旧版本安装过的包装器会被识别并重写，避免升级后仍跑旧脚本。
     */
    private static final String SANDBOX_WRAPPER_TAG = "AIStudio2API sandbox-warning wrapper v2";
    private static final String SANDBOX_AUTOCONFIG_MARK = "AIStudio2API sandbox notices";

    private void ensureCamoufoxSandboxWarningSuppressed() {
        ensureCamoufoxSandboxAutoconfig();
        File bin = new File(rootfs, "opt/camoufox/camoufox-bin");
        if (!bin.isFile()) {
            return;
        }
        File real = new File(rootfs, "opt/camoufox/camoufox-bin.real");
        try {
            String head = "";
            try (InputStream in = new FileInputStream(bin)) {
                byte[] buf = new byte[160];
                int n = in.read(buf);
                if (n > 0) {
                    head = new String(buf, 0, n, StandardCharsets.UTF_8);
                }
            }
            if (head.contains(SANDBOX_WRAPPER_TAG)) {
                return;
            }
            if (!real.isFile()) {
                if (head.contains("AIStudio2API sandbox-warning wrapper")) {
                    // 已经是旧包装器却没有 .real：复制会把包装器当成真二进制套娃，只能保持原样。
                    Log.w(TAG, "camoufox-bin is a stale wrapper without camoufox-bin.real");
                    return;
                }
                Files.copy(bin.toPath(), real.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                real.setExecutable(true, false);
            }
            String script = "#!/bin/sh\n"
                    + "# " + SANDBOX_WRAPPER_TAG + "\n"
                    + "profile=''\n"
                    + "prev=''\n"
                    + "for arg in \"$@\"; do\n"
                    + "  case \"$arg\" in -profile=*) profile=\"${arg#-profile=}\"; break;; esac\n"
                    + "  if [ \"$prev\" = \"-profile\" ]; then profile=\"$arg\"; break; fi\n"
                    + "  prev=\"$arg\"\n"
                    + "done\n"
                    + "if [ -n \"$profile\" ] && [ -d \"$profile\" ]; then\n"
                    + "  mkdir -p \"$profile/chrome\"\n"
                    + "  cat > \"$profile/chrome/userChrome.css\" <<'CSS'\n"
                    + "#notifications-toolbar, #tab-notification-deck, #notification-popup-box,\n"
                    + ".notificationbox-stack, .global-notificationbox, .popup-notification,\n"
                    + "#navigator-toolbox > .notificationbox-stack { display: none !important; }\n"
                    + "CSS\n"
                    + "  grep -q legacyUserProfileCustomizations.stylesheets \"$profile/user.js\" 2>/dev/null ||\n"
                    + "    printf '%s\\n' 'user_pref(\"toolkit.legacyUserProfileCustomizations.stylesheets\", true);' >> \"$profile/user.js\"\n"
                    + "  grep -q warn_unprivileged_namespaces \"$profile/user.js\" 2>/dev/null ||\n"
                    + "    printf '%s\\n' 'user_pref(\"security.sandbox.warn_unprivileged_namespaces\", false);' >> \"$profile/user.js\"\n"
                    + "fi\n"
                    + "exec \"$(dirname \"$0\")/camoufox-bin.real\" \"$@\"\n";
            Files.write(bin.toPath(), script.getBytes(StandardCharsets.UTF_8));
            bin.setExecutable(true, false);
        } catch (Exception e) {
            Log.w(TAG, "cannot install Camoufox warning wrapper", e);
        }
    }

    /**
     * 在 Camoufox 的 AutoConfig 末尾追加一段脚本，把沙箱告警从源头消掉。
     * Camoufox 通过 {@code defaults/pref/local-settings.js} 指定
     * {@code general.config.filename=camoufox.cfg}，所以该文件在父进程启动时执行。
     */
    private void ensureCamoufoxSandboxAutoconfig() {
        File cfg = new File(rootfs, "opt/camoufox/camoufox.cfg");
        if (!cfg.isFile()) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
            if (text.contains(SANDBOX_AUTOCONFIG_MARK)) {
                return;
            }
            String block = "\n"
                    + "// " + SANDBOX_AUTOCONFIG_MARK + "\n"
                    + "// PRoot 没有非特权命名空间，内容沙箱按设计关闭；把两条提示从源头去掉。\n"
                    + "try {\n"
                    + "  const { SandboxUtils } = ChromeUtils.importESModule(\"resource://gre/modules/SandboxUtils.sys.mjs\");\n"
                    + "  SandboxUtils.maybeWarnAboutDisabledContentSandbox = () => {};\n"
                    + "  SandboxUtils.maybeWarnAboutMissingUserNamespaces = () => {};\n"
                    + "} catch (e) {}\n"
                    + "try {\n"
                    + "  Services.obs.addObserver(function (subject) {\n"
                    + "    try {\n"
                    + "      const box = subject.gNotificationBox;\n"
                    + "      for (const value of [\"sandbox-content-disabled\", \"sandbox-unprivileged-namespaces\"]) {\n"
                    + "        const item = box && box.getNotificationWithValue(value);\n"
                    + "        if (item) box.removeNotification(item);\n"
                    + "      }\n"
                    + "    } catch (e) {}\n"
                    + "  }, \"browser-delayed-startup-finished\");\n"
                    + "} catch (e) {}\n";
            Files.write(cfg.toPath(), (text + block).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "cannot patch Camoufox autoconfig", e);
        }
    }

    private void ensureSharedDir() {
        sharedDir();
        File host = new File(rootfs, "mnt/host");
        if (!host.exists() && !host.mkdirs()) {
            Log.w(TAG, "cannot create rootfs shared mount point " + host);
        }
    }

    /**
     * Stops Camoufox instances left over from an earlier login attempt.
     *
     * The backend gives each account one profile directory. A browser that is
     * still alive keeps the profile lock, so the next 「浏览器登录」 window exits
     * immediately and the request fails with 登录未完成. Killing them right
     * before the login request leaves the page is what makes repeated clicks
     * work.
     */
    void killStaleBrowsers() {
        try {
            ProcessBuilder pb = new ProcessBuilder(prootCommandFor(
                    "/bin/bash", BACKEND_DIR + "/kill-browsers.sh"));
            // proot needs its loader/tmp/lib environment, otherwise it cannot
            // even start (libtalloc lives in files/runtime/lib).
            pb.environment().putAll(prootEnv());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot stop stale Camoufox processes", e);
        }
    }

    /**
     * Rebuilds the rootfs trust store.
     *
     * The slim Ubuntu image we ship was built without the ca-certificates
     * package, so /etc/ssl/certs is empty. Camoufox still browses fine (it has
     * its own store) but every OpenSSL/Go client inside the rootfs fails with
     * "x509: certificate signed by unknown authority" — which is exactly what
     * the Go backend hit when it fetched aistudio.google.com after login.
     *
     * The bundle is the Mozilla roots shipped in assets plus whatever the
     * device itself trusts, so certificates installed on the phone (private
     * CAs, enterprise proxies) keep working too. Rebuilt on every start, so a
     * CA added to the phone is picked up without reinstalling the app.
     */
    private void deployCaCertificates(AssetManager am) throws IOException {
        File certsDir = new File(rootfs, "etc/ssl/certs");
        if (!certsDir.exists() && !certsDir.mkdirs()) {
            throw new IOException("cannot create " + certsDir);
        }
        File bundle = new File(certsDir, "ca-certificates.crt");
        File staging = new File(certsDir, ".ca-certificates.crt.new");
        try (OutputStream out = new FileOutputStream(staging)) {
            try (InputStream in = am.open("ca-certificates.crt")) {
                copy(in, out);
            }
            for (File dir : deviceCaDirs()) {
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                Arrays.sort(files);
                for (File f : files) {
                    if (!f.isFile() || !f.canRead()) {
                        continue;
                    }
                    try (InputStream in = new FileInputStream(f)) {
                        out.write('\n');
                        copy(in, out);
                    } catch (IOException e) {
                        Log.w(TAG, "skipping CA " + f + ": " + e.getMessage());
                    }
                }
            }
        }
        if (!staging.renameTo(bundle)) {
            Files.deleteIfExists(bundle.toPath());
            if (!staging.renameTo(bundle)) {
                throw new IOException("cannot install " + bundle);
            }
        }
    }

    /**
     * CA directories exposed by Android: 14+ keeps the system store in the
     * Conscrypt APEX, older releases in /system, user-installed CAs in
     * /data/misc (readable only on some builds, hence the per-file guard).
     */
    private static List<File> deviceCaDirs() {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File("/apex/com.android.conscrypt/cacerts"));
        dirs.add(new File("/system/etc/security/cacerts"));
        dirs.add(new File("/data/misc/user/0/cacerts-added"));
        return dirs;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /**
     * Deploys the bundled noVNC client to /opt/novnc, served by websockify
     * during the login window.
     */
    private void deployNovnc(AssetManager am) throws IOException {
        File dest = new File(rootfs, "opt/novnc");
        File marker = new File(dest, NOVNC_MARKER);
        if (marker.exists() && new File(dest, "vnc.html").exists()) {
            return;
        }
        if (dest.exists()) {
            deleteRecursive(dest);
        }
        if (!dest.mkdirs()) {
            throw new IOException("cannot create noVNC dir " + dest);
        }
        copyAsset(am, "novnc", dest);
        if (!marker.createNewFile()) {
            Log.w(TAG, "could not create noVNC marker");
        }
    }

    /**
     * Deploys the pieces of the virtual desktop that live in the APK rather than
     * in the rootfs image:
     *
     * <ul>
     *   <li>{@code usr/bin/Xvnc} — TigerVNC's X server with an integrated RFB
     *       server ({@code Xtigervnc}, shipped as {@code assets/xvnc}). It is
     *       copied on every start so an app update also updates the display
     *       stack, and so installs created from an older rootfs (which only had
     *       Xvfb + x11vnc) gain it without re-extracting 2.3 GB.</li>
     *   <li>{@code usr/local/bin/tinywm} — the minimal window manager that fills
     *       Camoufox's login window with the desktop
     *       (see appgo/src/main/native/tinywm.c).</li>
     * </ul>
     */
    private void deployDisplayStack(AssetManager am) throws IOException {
        File xvnc = new File(rootfs, "usr/bin/Xtigervnc");
        copyAsset(am, "xvnc/Xtigervnc", xvnc);
        makeExecutable(xvnc);

        // vnc-serve.sh and anything else looking for the conventional name use
        // /usr/bin/Xvnc, which the distro package provides as a symlink.
        File link = new File(rootfs, "usr/bin/Xvnc");
        if (!Files.isSymbolicLink(link.toPath())) {
            link.delete();
            Files.createSymbolicLink(link.toPath(), new File("Xtigervnc").toPath());
        }

        File tinywm = new File(rootfs, "usr/local/bin/tinywm");
        copyAsset(am, "scripts/tinywm", tinywm);
        makeExecutable(tinywm);
    }

    File devDir() {
        return new File(base, "dev");
    }

    private void ensureDevDir() throws IOException {
        File d = devDir();
        File[] dirs = {d, new File(d, "shm"), new File(d, "pts")};
        for (File dir : dirs) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        }
    }

    private void writeResolvConf() throws IOException {
        List<String> servers = collectDnsServers();
        if (servers.isEmpty()) {
            servers.add("1.1.1.1");
            servers.add("8.8.8.8");
            servers.add("114.114.114.114");
        }
        StringBuilder sb = new StringBuilder();
        for (String s : servers) {
            sb.append("nameserver ").append(s).append('\n');
        }
        // Fail over fast: with a VPN up the first resolver can be a carrier
        // one that is unreachable through the tunnel.
        sb.append("options timeout:1 attempts:1\n");

        File etc = new File(rootfs, "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            throw new IOException("cannot create etc dir");
        }
        Files.write(new File(etc, "resolv.conf").toPath(),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeHostsFile() throws IOException {
        String content = "127.0.0.1\tlocalhost\n"
                + "::1\t\tlocalhost ip6-localhost ip6-loopback\n"
                + "127.0.0.1\tlocalhost.localdomain\n";
        File hosts = new File(rootfs, "etc/hosts");
        Files.write(hosts.toPath(), content.getBytes(StandardCharsets.UTF_8));
        hosts.setReadable(true, false);
    }

    /**
     * Resolvers for the rootfs, best first.
     *
     * <p>With a VPN up, the carrier resolvers of the other (now unused)
     * networks are unreachable through the tunnel: every lookup stalled on
     * them for the full timeout before falling back, which is what made the
     * login browser feel far slower than the phone's own browser. The active
     * network's resolvers (the tunnel's, when one is up) go first, public
     * resolvers second, and everything else last.
     */
    private List<String> collectDnsServers() {
        List<String> out = new ArrayList<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return out;
            }
            addDnsServers(cm.getLinkProperties(cm.getActiveNetwork()), out);
            addDns(out, "1.1.1.1");
            addDns(out, "8.8.8.8");
            for (android.net.Network net : cm.getAllNetworks()) {
                addDnsServers(cm.getLinkProperties(net), out);
            }
        } catch (Exception e) {
            Log.w(TAG, "collectDnsServers failed", e);
        }
        return out;
    }

    private static void addDnsServers(android.net.LinkProperties lp, List<String> out) {
        if (lp == null) {
            return;
        }
        for (java.net.InetAddress addr : lp.getDnsServers()) {
            if (addr instanceof java.net.Inet6Address) {
                continue;
            }
            addDns(out, addr.getHostAddress());
        }
    }

    private static void addDns(List<String> out, String ip) {
        if (ip != null && !ip.isEmpty() && !out.contains(ip)) {
            out.add(ip);
        }
    }

    // ------------------------------------------------------------------
    // .env for the Go backend
    // ------------------------------------------------------------------

    /** Writes the .env consumed by the Go backend. */
    void writeEnvFile(String apiKey) throws IOException {
        if (!backendDir.exists() && !backendDir.mkdirs()) {
            throw new IOException("cannot create backend dir");
        }
        // Merge instead of overwrite: the console's settings page writes the
        // same .env (config.Config.Save) and those values — listen address,
        // admin auth, proxy, routing, model mapping, worker limits, log
        // settings — must survive an app or service restart. The launcher only
        // owns the keys below.
        Map<String, String> values = readEnvFile();
        values.put("AISTUDIO_AUTH_STATES", "auth");
        // LISTEN_ADDR (host and port) and ADMIN_* belong to the console. Only
        // provide the first-run default; never overwrite what the user saved.
        putDefault(values, "LISTEN_ADDR", LOOPBACK + ":" + PORT);
        // PROXY_API_KEY also belongs to the console: only generate it when .env
        // does not have the key yet. An explicitly emptied key stays empty, and
        // either way the value is mirrored into config/app.json so the menu's
        // update check authenticates with the same key.
        boolean hasEnvKey = values.containsKey("PROXY_API_KEY");
        String effectiveKey = hasEnvKey
                ? (values.get("PROXY_API_KEY") == null ? "" : values.get("PROXY_API_KEY").trim())
                : (apiKey == null ? "" : apiKey.trim());
        values.put("PROXY_API_KEY", effectiveKey);
        writeAppConfig(effectiveKey);
        // The rootfs now ships Camoufox 152.0.4-beta.29, the version AIStudio2API
        // v0.2.13 expects. Keep the browser-backed WAA as the default; `go` is
        // still available by setting WAA_BACKEND=go for a browserless runtime.
        // First-run defaults only; a value saved in the console wins.
        putDefault(values, "WAA_BACKEND", "camoufox");
        putDefault(values, "AUTO_START", "false");
        putDefault(values, "LOG_FILE", "true");
        putDefault(values, "LOG_MAX_TOTAL_MB", "200");
        // The management UI's 「浏览器登录」 keeps its HTTP request open until
        // the user finishes Google sign-in, so this is also the login timeout.
        putDefault(values, "REQUEST_TIMEOUT", "10m");
        // Mobile-friendly defaults: fewer resident Camoufox workers.
        putDefault(values, "WARM_WORKER_LIMIT", "1");
        putDefault(values, "MAX_ACTIVE_WORKERS", "2");
        putDefault(values, "WARM_STARTUP_CONCURRENCY", "1");
        putDefault(values, "PER_ACCOUNT_CONCURRENCY", "1");

        StringBuilder sb = new StringBuilder();
        sb.append("# 由 Android 启动器维护；管理页保存的设置会保留，重启服务后生效\n");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        // Write-then-rename so a crash cannot leave a truncated .env behind.
        File staging = new File(backendDir, ".env.tmp");
        Files.write(staging.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(staging.toPath(), envFile().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        envFile().setReadable(true, true);
        envFile().setWritable(true, true);
    }

    /** Reads KEY=VALUE pairs so console changes survive a launcher restart. */
    private Map<String, String> readEnvFile() {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            if (!envFile().exists()) {
                return values;
            }
            for (String raw : Files.readAllLines(envFile().toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (!key.isEmpty()) {
                    values.put(key, line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot read .env: " + e.getMessage());
        }
        return values;
    }

    private static void putDefault(Map<String, String> values, String key, String value) {
        String current = values.get(key);
        if (current == null || current.trim().isEmpty()) {
            values.put(key, value);
        }
    }

    // ------------------------------------------------------------------
    // proot plumbing
    // ------------------------------------------------------------------

    File prootBinary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    File loaderBinary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot_loader.so");
    }

    /** proot argv that launches the Go backend service. */
    String[] prootCommand() {
        return prootCommandFor("/bin/bash", BACKEND_DIR + "/start-aistudio2api.sh");
    }

    /**
     * Command (inside the rootfs) that brings up the virtual desktop the
     * backend's headful Camoufox login window renders on:
     *   Xvnc :99 (X + RFB)  ->  websockify/noVNC on 127.0.0.1:6080
     */
    static String[] vncServeCommandInsideRootfs() {
        return new String[]{"/bin/bash", BACKEND_DIR + "/vnc-serve.sh"};
    }

    /** proot argv for an arbitrary command inside the rootfs. */
    String[] prootCommandFor(String... commandInsideRootfs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBinary().getAbsolutePath());
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-w");
        cmd.add(BACKEND_DIR);
        for (String bind : devBinds()) {
            cmd.add("-b");
            cmd.add(bind);
        }
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(ctx.getCacheDir().getAbsolutePath() + ":/sdcard-cache");
        // Persistent, user-visible shared folder. Put files here from Android
        // (or adb) and access them inside the rootfs / browser at /mnt/host.
        cmd.add("-b");
        cmd.add(sharedDir().getAbsolutePath() + ":/mnt/host");
        for (String a : commandInsideRootfs) {
            cmd.add(a);
        }
        return cmd.toArray(new String[0]);
    }

    /**
     * External-files "shared" directory, mounted at /mnt/host in the rootfs.
     * getExternalFilesDir needs no storage permission and survives app updates.
     */
    File sharedDir() {
        File ext = ctx.getExternalFilesDir(null);
        File dir = new File(ext != null ? ext : ctx.getFilesDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create shared dir " + dir);
        }
        return dir;
    }

    String sharedDirPath() {
        return sharedDir().getAbsolutePath();
    }

    List<String> devBinds() {
        List<String> binds = new ArrayList<>();
        binds.add(devDir().getAbsolutePath() + ":/dev");
        String[] devNodes = {
                "/dev/null", "/dev/zero",
                "/dev/random", "/dev/urandom", "/dev/tty",
        };
        for (String node : devNodes) {
            if (new File(node).exists()) {
                binds.add(node);
            }
        }
        return binds;
    }

    /** The phone's IANA timezone, used for new accounts and log timestamps. */
    private static String deviceTimezone() {
        try {
            String id = java.util.TimeZone.getDefault().getID();
            if (id != null && !id.isEmpty() && !"GMT".equals(id)) {
                return id;
            }
        } catch (Exception ignored) {
        }
        return "UTC";
    }

    /** The phone's locale in the form the backend normalises (zh_CN.UTF-8). */
    private static String deviceLocale() {
        try {
            java.util.Locale locale = java.util.Locale.getDefault();
            String language = locale.getLanguage();
            if (language != null && !language.isEmpty() && !"und".equals(language)) {
                String country = locale.getCountry();
                String tag = country == null || country.isEmpty()
                        ? language : language + "_" + country;
                return tag + ".UTF-8";
            }
        } catch (Exception ignored) {
        }
        return "C.UTF-8";
    }

    Map<String, String> prootEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());
        env.put("PROOT_LOADER", loaderBinary().getAbsolutePath());
        env.put("PROOT_TMP_DIR", new File(base, "tmp").getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        // Camoufox/Firefox's content-process sandbox cannot be set up under PRoot
        // (SELinux + missing namespaces), which kills every renderer with SIGSEGV.
        env.put("MOZ_DISABLE_CONTENT_SANDBOX", "1");
        env.put("CAMOUFOX_PATH", CAMOUFOX_PATH);
        // The backend opens its headful Camoufox login window on the virtual
        // display provided by vnc-serve.sh; Xvnc/websockify read the same values
        // from RuntimeEnv.DISPLAY.
        // The WAA workers are launched with -headless, so they ignore DISPLAY.
        env.put("DISPLAY", DISPLAY);
        // Xvnc's geometry: by default exactly the login view's pixel area, so
        // the browser fills the screen 1:1 (see virtualScreenSize), or the
        // geometry picked in the 「显示分辨率」 menu.
        int[] screen = displaySize(ctx);
        env.put("VNC_SCREEN", screen[0] + "x" + screen[1]);
        env.put("HOME", "/root");
        env.put("TMPDIR", "/tmp");
        // Follow the phone, like the upstream build follows the desktop: the
        // backend derives a new account's locale/timezone from these values.
        env.put("TZ", deviceTimezone());
        env.put("LANG", deviceLocale());
        // LANG tells the backend which locale a new account should use, but the
        // rootfs only ships C.UTF-8; LC_ALL keeps libc (and bash) from warning
        // about a missing locale on every command while Go still reads LANG.
        env.put("LC_ALL", "C.UTF-8");
        // The virtual display is a software X server; tell Mesa/GLX to match
        // (upstream sets the same pair for the X server it starts itself).
        env.put("LIBGL_ALWAYS_SOFTWARE", "1");
        env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
        // The rootfs ships no trust store of its own; ensureReady() rebuilds this
        // bundle, and Go/OpenSSL read it from here.
        env.put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt");
        env.put("SSL_CERT_DIR", "/etc/ssl/certs");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        return env;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void extractTar(File tarZst, File dest) throws IOException {
        // Decompress the .tar.zst with zstd-jni and feed the plain tar stream to
        // the device's tar. The static zstd helper binary gets killed by Android's
        // seccomp filter (SIGSYS), so JNI decompression is the reliable path.
        String tar = findTar();
        if (tar == null) {
            throw new IOException("no tar available on device");
        }
        ProcessBuilder pb = new ProcessBuilder(tar, "-xf", "-");
        pb.directory(dest);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        Thread pump = new Thread(() -> {
            try (InputStream zstd = new com.github.luben.zstd.ZstdInputStream(
                         new java.io.FileInputStream(tarZst));
                 OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = zstd.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "zstd decompress pump failed", e);
            } finally {
                try {
                    p.getOutputStream().close();
                } catch (IOException ignored) {
                }
            }
        }, "tar-pump");
        pump.setDaemon(true);
        pump.start();

        StringBuilder err = new StringBuilder();
        Thread errDrain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.d(TAG, "[tar] " + line);
                    synchronized (err) {
                        if (err.length() < 4000) {
                            err.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "tar-err");
        errDrain.setDaemon(true);
        errDrain.start();

        try {
            int code = p.waitFor();
            pump.join(2000);
            errDrain.join(2000);
            // Android SELinux forbids hardlinks inside app-private storage, so tar
            // reports link errors and exits non-zero even though the rest extracted.
            if (code != 0 && !isOnlyHardlinkErrors(err.toString())) {
                String detail = err.toString().trim();
                throw new IOException("tar=" + code + (detail.isEmpty() ? "" : " " + detail));
            }
            if (!new File(dest, "bin/bash").exists() || !backendBinary().exists()) {
                throw new IOException("rootfs extraction incomplete: " + err.toString().trim());
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted");
        }
    }

    private static boolean isOnlyHardlinkErrors(String stderr) {
        boolean sawError = false;
        for (String raw : stderr.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("tar: had errors")) {
                continue;
            }
            if (line.startsWith("tar: can't link") && line.contains("Permission denied")) {
                continue;
            }
            sawError = true;
        }
        return !sawError;
    }

    private String findTar() {
        for (String cand : new String[]{"/system/bin/tar", "/system/xbin/tar", "tar"}) {
            if (cand.equals("tar") || new File(cand).canExecute()) {
                return cand;
            }
        }
        return null;
    }

    private void copyAsset(AssetManager am, String assetPath, File dest) throws IOException {
        String[] children = null;
        try {
            children = am.list(assetPath);
        } catch (IOException ignored) {
        }
        if (children != null && children.length > 0) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("cannot create dir " + dest);
            }
            for (String child : children) {
                copyAsset(am, assetPath + "/" + child, new File(dest, child));
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create dir " + parent);
            }
            try (InputStream in = am.open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    private static void makeExecutable(File f) {
        if (f.exists()) {
            f.setExecutable(true, false);
        }
    }

    private static void makeLibsExecutable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                makeLibsExecutable(f);
            } else {
                String n = f.getName();
                if (n.contains(".so") || n.equals("node") || n.equals("camoufox")
                        || n.equals("camoufox-bin") || n.endsWith(".sh")) {
                    f.setExecutable(true, false);
                }
            }
        }
    }

    private static String randomToken(int length) {
        SecureRandom r = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
