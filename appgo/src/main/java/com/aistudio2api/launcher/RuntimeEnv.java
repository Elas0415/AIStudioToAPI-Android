package com.aistudio2api.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *     usr/bin/Xvfb, x11vnc, websockify  - virtual desktop stack (from base rootfs)
 *
 * The virtual desktop exists because the backend's account creation (the 「浏览器
 * 登录」 button in its management UI) drives a *visible* Camoufox browser. The app
 * keeps Xvfb :99 alive next to the service and forwards it as noVNC on
 * 127.0.0.1:6080, which the WebView shows while such a request is in flight.
 */
final class RuntimeEnv {

    private static final String TAG = "RuntimeEnv";

    /** Bumped whenever the extraction/deploy steps change. */
    static final String ROOT_MARKER = ".extracted-go-v1";
    /**
     * Bumped whenever anything under {@code assets/novnc} changes, otherwise an
     * existing install keeps the previously deployed copy (the deploy is
     * marker-gated). v2: login.html stopped overriding noVNC's canvas sizing.
     */
    static final String NOVNC_MARKER = ".novnc-go-v2";

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
     * Virtual desktop size, derived from the phone's *portrait* aspect ratio so
     * that noVNC's fit-to-view wastes as little screen as possible.
     *
     * The short side is kept at 1080px: a fresh Camoufox profile picks its own
     * window size, and a screen narrower than that would push the browser window
     * off the edge of the remote desktop.
     */
    static int[] virtualScreenSize(Context ctx) {
        int shortSide = 1080;
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int a = Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
            int b = Math.max(1, Math.max(dm.widthPixels, dm.heightPixels));
            return new int[]{shortSide, Math.round(shortSide * (float) b / a)};
        } catch (Exception e) {
            return new int[]{shortSide, 1920};
        }
    }

    // ------------------------------------------------------------------
    // Display resolution (Termux:X11 style)
    // ------------------------------------------------------------------

    /*
     * The virtual desktop the login window is drawn on exists only as Xvfb's
     * -screen geometry, so changing it means restarting the display session.
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
     * Geometry of the virtual desktop: the user's choice when one is set,
     * otherwise the phone's own portrait aspect. A bigger desktop means a
     * Camoufox window (which sizes itself) is fully visible instead of being
     * clipped by the framebuffer edge.
     */
    static int[] displaySize(Context ctx) {
        int[] custom = customDisplaySize(ctx);
        return custom != null ? custom : virtualScreenSize(ctx);
    }

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
    // App config (host/port/apiKey/adminPassword), persisted as JSON.
    // ------------------------------------------------------------------

    String apiKey() {
        return appConfig("apiKey");
    }

    String adminPassword() {
        return appConfig("adminPassword");
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
    synchronized String ensureAppConfig(boolean lanMode) throws IOException {
        String key = apiKey();
        if (key == null || key.isEmpty()) {
            key = DEFAULT_API_KEY;
        }
        String admin = adminPassword();
        if (lanMode && (admin == null || admin.isEmpty())) {
            admin = randomToken(16);
        }
        writeAppConfig(lanMode, key, admin);
        return key;
    }

    private void writeAppConfig(boolean lanMode, String key, String admin) throws IOException {
        File f = appConfigFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create config dir");
        }
        try {
            JSONObject o = new JSONObject();
            o.put("host", lanMode ? "0.0.0.0" : LOOPBACK);
            o.put("port", PORT);
            o.put("apiKey", key == null ? "" : key);
            o.put("adminPassword", admin == null ? "" : admin);
            Files.write(f.toPath(), o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("encode app config: " + e.getMessage(), e);
        }
    }

    boolean isLanEnabled() {
        try {
            return "0.0.0.0".equals(new JSONObject(
                    new String(Files.readAllBytes(appConfigFile().toPath()), StandardCharsets.UTF_8))
                    .optString("host", LOOPBACK));
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Runtime preparation
    // ------------------------------------------------------------------

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
            if (rootfs.exists()) {
                deleteRecursive(rootfs);
            }
            if (!rootfs.mkdirs()) {
                throw new IOException("cannot create rootfs dir");
            }
            extractTar(tarZst, rootfs);
            tarZst.delete();

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
        deployNovnc(am);
        deployXvfbWrappers(am);
        ensureDevDir();
        writeResolvConf();
        writeHostsFile();

        // The backend reads .env from its working directory; make sure one exists
        // even when a maintenance command (setup --storage-state) runs before the
        // service has ever been started.
        if (!envFile().exists()) {
            boolean lan = isLanEnabled();
            writeEnvFile(ensureAppConfig(lan), lan);
        }
    }

    private void deployScripts(AssetManager am) throws IOException {
        copyAsset(am, "scripts/start-aistudio2api.sh",
                new File(backendDir, "start-aistudio2api.sh"));
        makeExecutable(new File(backendDir, "start-aistudio2api.sh"));
        copyAsset(am, "scripts/vnc-serve.sh", new File(backendDir, "vnc-serve.sh"));
        makeExecutable(new File(backendDir, "vnc-serve.sh"));
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
     * Xvfb / x11vnc wrappers injected into /usr/local/bin (first on PATH):
     *   - Xvfb gets -nolock (Android SELinux forbids Xorg's lock link()).
     *   - x11vnc gets -noshm (some Android kernels lack SysV shm syscalls).
     */
    private void deployXvfbWrappers(AssetManager am) throws IOException {
        File xvfb = new File(rootfs, "usr/local/bin/Xvfb");
        copyAsset(am, "scripts/Xvfb", xvfb);
        makeExecutable(xvfb);
        File x11vnc = new File(rootfs, "usr/local/bin/x11vnc");
        copyAsset(am, "scripts/x11vnc", x11vnc);
        makeExecutable(x11vnc);
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
        sb.append("options timeout:3 attempts:2\n");

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

    private List<String> collectDnsServers() {
        List<String> out = new ArrayList<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return out;
            }
            for (android.net.Network net : cm.getAllNetworks()) {
                android.net.LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) {
                    continue;
                }
                for (java.net.InetAddress addr : lp.getDnsServers()) {
                    if (addr instanceof java.net.Inet6Address) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip != null && !out.contains(ip)) {
                        out.add(ip);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectDnsServers failed", e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // .env for the Go backend
    // ------------------------------------------------------------------

    /** Writes the .env consumed by the Go backend. */
    void writeEnvFile(String apiKey, boolean lan) throws IOException {
        if (!backendDir.exists() && !backendDir.mkdirs()) {
            throw new IOException("cannot create backend dir");
        }
        String host = lan ? "0.0.0.0" : LOOPBACK;
        // 局域网访问必须同时开启管理端登录：上游控制台默认只接受本机请求，
        // 其他设备会收到 control_plane_forbidden（"enable console sign-in for
        // remote access"）。密码由 ensureAppConfig() 生成并持久化，和开启时
        // 弹窗里展示的、以及「局域网访问」对话框里显示的完全一致。
        // 关闭局域网时保持免登录，本机 WebView 打开即可用。
        String admin = adminPassword();
        if (lan && (admin == null || admin.isEmpty())) {
            admin = randomToken(16);
            writeAppConfig(true, apiKey, admin);
        }
        boolean adminAuth = lan && admin != null && !admin.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("# 由 Android 启动器生成，修改后重启服务生效\n");
        sb.append("AISTUDIO_AUTH_STATES=auth\n");
        sb.append("LISTEN_ADDR=").append(host).append(':').append(PORT).append('\n');
        sb.append("PROXY_API_KEY=").append(apiKey == null ? "" : apiKey).append('\n');
        sb.append("AUTO_START=false\n");
        sb.append("LOG_FILE=true\n");
        sb.append("LOG_MAX_TOTAL_MB=200\n");
        sb.append("WAA_BACKEND=camoufox\n");
        sb.append("ADMIN_AUTH_ENABLED=").append(adminAuth ? "true" : "false").append('\n');
        sb.append("ADMIN_USERNAME=admin\n");
        sb.append("ADMIN_PASSWORD=").append(adminAuth ? admin : "").append('\n');
        // The management UI's 「浏览器登录」 button creates an account by driving
        // a visible Camoufox; that HTTP request stays open until the user
        // finishes Google sign-in, so the request timeout doubles as the login
        // timeout and must be generous (the upstream default is 5m).
        sb.append("REQUEST_TIMEOUT=10m\n");
        // Mobile-friendly defaults: fewer resident Camoufox workers.
        sb.append("WARM_WORKER_LIMIT=1\n");
        sb.append("MAX_ACTIVE_WORKERS=2\n");
        sb.append("WARM_STARTUP_CONCURRENCY=1\n");
        sb.append("PER_ACCOUNT_CONCURRENCY=1\n");
        Files.write(envFile().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
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
     *   Xvfb :99  ->  x11vnc :5900  ->  websockify/noVNC on 127.0.0.1:6080
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
        for (String a : commandInsideRootfs) {
            cmd.add(a);
        }
        return cmd.toArray(new String[0]);
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
        // display provided by vnc-serve.sh; Xvfb/x11vnc/websockify read the
        // same values from RuntimeEnv.DISPLAY.
        // The WAA workers are launched with -headless, so they ignore DISPLAY.
        env.put("DISPLAY", DISPLAY);
        // Xvfb's geometry, driven by the 「显示分辨率」 setting.
        int[] screen = displaySize(ctx);
        env.put("VNC_SCREEN", screen[0] + "x" + screen[1]);
        env.put("HOME", "/root");
        env.put("TMPDIR", "/tmp");
        env.put("TZ", "UTC");
        env.put("LANG", "C.UTF-8");
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
