package com.aistudio.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

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
 * hosts Node.js 24 + AIStudioToAPI + Camoufox.
 *
 * Layout under filesDir/runtime/:
 *   lib/libtalloc.so.2, lib/libandroid-shmem.so  - proot deps
 *   tmp/                                            - proot temp
 *   rootfs/                                         - Ubuntu 24.04 arm64
 *     opt/a2a/          - AIStudioToAPI (node app, ui/dist prebuilt)
 *     opt/camoufox/     - Camoufox arm64 browser
 *     usr/local/bin/node- Node.js 24 arm64
 *   rootfs/data/a2a.env - runtime env file (API key etc.)
 */
final class RuntimeEnv {

    private static final String TAG = "RuntimeEnv";

    static final String ROOT_MARKER = ".extracted-v2";
    static final String NOVNC_MARKER = ".novnc-v1";
    static final int PORT = 7860;

    private final Context ctx;
    private final File base;
    private final File rootfs;
    private final File a2aDir;

    RuntimeEnv(Context ctx) {
        this.ctx = ctx;
        this.base = new File(ctx.getFilesDir(), "runtime");
        this.rootfs = new File(base, "rootfs");
        this.a2aDir = new File(rootfs, "opt/a2a");
    }

    File a2aDir() {
        return a2aDir;
    }

    /** Reads the currently installed backend version from the rootfs package.json. */
    String installedBackendVersion() {
        try {
            File pkg = new File(a2aDir, "package.json");
            if (pkg.exists()) {
                String json = new String(Files.readAllBytes(pkg.toPath()), StandardCharsets.UTF_8);
                int i = json.indexOf("\"version\"");
                if (i >= 0) {
                    int s = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
                    int e = json.indexOf('"', s);
                    return json.substring(s, e);
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    /**
     * Installs a backend update into the rootfs /opt/a2a.
     *
     * When {@code haveBundle} is true the file is a gzip'd tar containing a full
     * backend (source + node_modules + ui/dist) and is extracted over /opt/a2a,
     * preserving configs/, data/ and auth files. Otherwise we fetch the upstream
     * source tarball for {@code tag} and overlay only the JS sources (src/,
     * scripts/, main.js, configs/, vite.config.js), keeping the existing
     * node_modules and ui/dist.
     */
    void installBackendUpdate(File bundle, boolean haveBundle, String tag) throws IOException {
        // Always back up configs/auth so a botched update can't lose accounts.
        File auth = authDir();
        File authBackup = new File(base, "auth-backup");
        if (auth.exists()) {
            deleteRecursive(authBackup);
            copyDir(auth, authBackup);
        }

        File tmp = new File(base, "update-tmp");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create update temp dir");
        }

        if (haveBundle) {
            extractTarGz(bundle, tmp);
            // Bundle may wrap everything in a single top folder; find the dir that
            // contains package.json.
            File src = findDirWithPackageJson(tmp);
            if (src == null) {
                throw new IOException("bundle missing package.json");
            }
            overlayTree(src, a2aDir);
        } else {
            // Download upstream source tarball and overlay JS only.
            File tarball = new File(base, "a2a-src-" + tag + ".tar.gz");
            downloadUrl("https://github.com/iBUHub/AIStudioToAPI/archive/refs/tags/"
                    + tag + ".tar.gz", tarball);
            extractTarGz(tarball, tmp);
            tarball.delete();
            File src = findDirWithPackageJson(tmp);
            if (src == null) {
                throw new IOException("source archive missing package.json");
            }
            overlaySourceOnly(src, a2aDir);
        }

        deleteRecursive(tmp);

        // Restore auth if the update removed it.
        if (authBackup.exists() && (!auth.exists() || auth.list() == null || auth.list().length == 0)) {
            if (!auth.exists()) {
                auth.mkdirs();
            }
            copyDir(authBackup, auth);
        }

        // Re-apply the platform patch so our tweaks survive the update.
        reapplyPatches();
    }

    private void reapplyPatches() {
        // Auth patch (tuned delays + IPv4) may already be deployed via ensureReady;
        // nothing to do here beyond ensuring the file is executable.
        new File(a2aDir, "start-a2a.sh").setExecutable(true, false);
    }

    /** Finds a directory (at any depth <= 3) containing a package.json. */
    private static File findDirWithPackageJson(File root) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        if (new File(root, "package.json").exists()) {
            return root;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File c : children) {
            File found = findDirWithPackageJson(c);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Copies all entries of src into dst, overwriting files but keeping extras. */
    private static void overlayTree(File src, File dst) throws IOException {
        File[] children = src.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            File target = new File(dst, c.getName());
            if (c.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("cannot create " + target);
                }
                overlayTree(c, target);
            } else {
                copyFile(c, target);
            }
        }
    }

    /** Overlays only backend JS sources, preserving node_modules and ui/dist. */
    private static void overlaySourceOnly(File src, File dst) throws IOException {
        String[] names = {"src", "scripts", "configs", "main.js", "package.json", "vite.config.js"};
        for (String name : names) {
            File s = new File(src, name);
            if (!s.exists()) {
                continue;
            }
            File t = new File(dst, name);
            if (s.isDirectory()) {
                if (!t.exists() && !t.mkdirs()) {
                    throw new IOException("cannot create " + t);
                }
                overlayTree(s, t);
            } else {
                copyFile(s, t);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        Files.copy(src.toPath(), dst.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void copyDir(File src, File dst) throws IOException {
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("cannot create " + dst);
        }
        File[] children = src.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            File t = new File(dst, c.getName());
            if (c.isDirectory()) {
                copyDir(c, t);
            } else {
                Files.copy(c.toPath(), t.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void downloadUrl(String urlStr, File dest) throws IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "AIStudioToAPI-Android");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " for " + urlStr);
        }
        try (java.io.InputStream in = conn.getInputStream();
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Extracts a gzip'd tar using the device's toybox tar. */
    private void extractTarGz(File archive, File dest) throws IOException {
        String tar = findTar();
        if (tar == null) {
            throw new IOException("no tar available on device");
        }
        ProcessBuilder pb = new ProcessBuilder(tar, "xzf", archive.getAbsolutePath(),
                "-C", dest.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 tar: " + e.getMessage(), e);
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 4000) {
                    out.append(line).append('\n');
                }
            }
        }
        try {
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("tar exit " + code + ": " + out);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted");
        }
    }

    File authDir() {
        return new File(a2aDir, "configs/auth");
    }

    File envFile() {
        return new File(rootfs, "data/a2a.env");
    }

    File appConfigFile() {
        return new File(ctx.getFilesDir(), "config/app.json");
    }

    File prootDir() {
        return base;
    }

    /**
     * App-private directory that is bind-mounted over /dev inside proot.
     *
     * Android's SELinux policy denies untrusted apps readdir("/dev"), which makes
     * Firefox's content sandbox abort ("Sandbox: Couldn't list /dev") and crash
     * every content process with SIGSEGV. Android also has no /dev/shm. We expose
     * a real, listable directory instead; individual device nodes (null, zero,
     * urandom, ...) are bind-mounted into it individually by prootCommand().
     */
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

    /** Absolute path of the rootfs on the Android filesystem (outside proot). */
    String rootfsPath() {
        return rootfs.getAbsolutePath();
    }

    /**
     * Builds the saveAuth.js argv for the headless auto-fill login (方案一).
     * Runs: node scripts/auth/saveAuth.js --non-interactive --headless ...
     */
    String[] saveAuthCommand(String email, String password, String totpSecret, String recoveryEmail,
                             long loginTimeoutMs) {
        List<String> args = new ArrayList<>();
        args.add("/usr/local/bin/node");
        args.add("scripts/auth/saveAuth.js");
        args.add("--non-interactive");
        args.add("--headless");
        args.add("--login-timeout-ms");
        args.add(String.valueOf(loginTimeoutMs));
        if (email != null && !email.trim().isEmpty()) {
            args.add("--email");
            args.add(email.trim());
        }
        if (password != null && !password.isEmpty()) {
            args.add("--password");
            args.add(password);
        }
        if (totpSecret != null && !totpSecret.trim().isEmpty()) {
            args.add("--totp-secret");
            args.add(totpSecret.trim());
        }
        if (recoveryEmail != null && !recoveryEmail.trim().isEmpty()) {
            args.add("--recovery-email");
            args.add(recoveryEmail.trim());
        }
        return args.toArray(new String[0]);
    }

    File logsDir() {
        return new File(ctx.getFilesDir(), "logs");
    }

    /** Directory for files downloaded from the WebUI (blob/data downloads). */
    File downloadsDir() {
        File dir = new File(ctx.getFilesDir(), "downloads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    String apiKey() {
        try {
            File f = appConfigFile();
            if (f.exists()) {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                int i = json.indexOf("\"apiKey\"");
                if (i >= 0) {
                    int s = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
                    int e = json.indexOf('"', s);
                    return json.substring(s, e);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Default console/login API key used when no config exists yet. */
    static final String DEFAULT_API_KEY = "123456";

    /** Persists a user-provided API key, preserving the current LAN mode. */
    synchronized void setApiKey(String key) throws IOException {
        File f = appConfigFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create config dir");
        }
        boolean lan = isLanEnabled();
        String json = "{\n  \"host\": \"" + (lan ? "0.0.0.0" : "127.0.0.1") + "\",\n"
                + "  \"port\": " + PORT + ",\n"
                + "  \"apiKey\": \"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n}\n";
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads or creates the persisted app config (host/port/apiKey). */
    synchronized String ensureAppConfig(boolean lanMode) throws IOException {
        File f = appConfigFile();
        String key = apiKey();
        if (key == null || key.isEmpty()) {
            key = DEFAULT_API_KEY;
        }
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create config dir");
        }
        String json = "{\n  \"host\": \"" + (lanMode ? "0.0.0.0" : "127.0.0.1") + "\",\n"
                + "  \"port\": " + PORT + ",\n"
                + "  \"apiKey\": \"" + key + "\"\n}\n";
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return key;
    }

    boolean isLanEnabled() {
        try {
            File f = appConfigFile();
            if (f.exists()) {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                return json.contains("\"0.0.0.0\"");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String randomKey() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder("sk-");
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
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
        if (!logsDir().exists() && !logsDir().mkdirs()) {
            throw new IOException("cannot create logs dir");
        }

        AssetManager am = ctx.getAssets();

        boolean fresh = !marker.exists();
        if (fresh) {
            Log.i(TAG, "first run: extracting runtime (this takes a few minutes)");
            copyAsset(am, "libtalloc.so.2", new File(libDir, "libtalloc.so.2"));
            copyAsset(am, "libandroid-shmem.so", new File(libDir, "libandroid-shmem.so"));

            // Extract rootfs archive via system tar (zstd).
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

            // Deploy launcher script.
            copyAsset(am, "scripts/start-a2a.sh", new File(a2aDir, "start-a2a.sh"));
            new File(a2aDir, "start-a2a.sh").setExecutable(true, false);

            // Ensure persistent dirs inside rootfs.
            authDir().mkdirs();
            new File(rootfs, "data").mkdirs();
            new File(a2aDir, "data").mkdirs();

            if (!marker.createNewFile()) {
                Log.w(TAG, "could not create marker");
            }
        }

        authDir().mkdirs();
        new File(a2aDir, "data").mkdirs();
        new File(a2aDir, "start-a2a.sh").setExecutable(true, false);
        new File(rootfs, "opt/camoufox/camoufox").setExecutable(true, false);
        new File(rootfs, "usr/local/bin/node").setExecutable(true, false);

        // Deploy the Xvfb wrapper (adds -nolock). Android SELinux forbids the
        // link() that Xorg uses for its server lock file, so Xvfb aborts with
        // "Linking lock file (/tmp/.X99-lock) failed: Permission denied" and the
        // backend's VNC login cannot start. The wrapper lives in /usr/local/bin
        // (first on PATH) and is refreshed on every start so upgrades apply.
        File xvfbWrapper = new File(rootfs, "usr/local/bin/Xvfb");
        copyAsset(am, "scripts/Xvfb", xvfbWrapper);
        xvfbWrapper.setExecutable(true, false);

        // Deploy the x11vnc wrapper (adds -noshm). Some Android kernels don't
        // implement the SysV shm syscalls x11vnc uses, so it aborts with
        // "shmget(scanline) failed: Function not implemented". Same PATH trick.
        File x11vncWrapper = new File(rootfs, "usr/local/bin/x11vnc");
        copyAsset(am, "scripts/x11vnc", x11vncWrapper);
        x11vncWrapper.setExecutable(true, false);

        deployNovnc(am);
        deployAuthPatch(am);
        ensureDevDir();
        writeResolvConf();
        writeHostsFile();
    }

    /**
     * Overlays our patched backend scripts onto the rootfs.
     *
     * The bundled rootfs.tar.zst is a prebuilt asset, so we cannot edit its
     * files in place. We overlay:
     *   - scripts/auth/saveAuth.js: tuned delays (randomWait 300-800ms) + IPv4 prefs
     *   - src/core/BrowserManager.js: IPv4-forcing Firefox prefs
     * The patch is idempotent and re-applied on every start so upgrades apply.
     */
    private void deployAuthPatch(AssetManager am) throws IOException {
        overlay(am, "patches/auth/saveAuth.js", new File(a2aDir, "scripts/auth/saveAuth.js"));
        overlay(am, "patches/core/BrowserManager.js", new File(a2aDir, "src/core/BrowserManager.js"));
    }

    private void overlay(AssetManager am, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        copyAsset(am, assetPath, dest);
    }

    /**
     * Deploys the bundled noVNC client into the backend static directory
     * (ui/dist/novnc) so it is served same-origin at /novnc/ and can open the
     * authenticated /vnc WebSocket without CDN or cross-origin issues.
     */
    private void deployNovnc(AssetManager am) throws IOException {
        File dest = new File(a2aDir, "ui/dist/novnc");
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

    /** Writes the env file consumed by start-a2a.sh (API key, host). */
    void writeEnvFile(String apiKey, boolean lan) throws IOException {
        File data = new File(rootfs, "data");
        if (!data.exists() && !data.mkdirs()) {
            throw new IOException("cannot create data dir");
        }
        String host = lan ? "0.0.0.0" : "127.0.0.1";
        String content = "API_KEYS=" + apiKey + "\nHOST=" + host + "\nPORT=" + PORT + "\n";
        Files.write(envFile().toPath(), content.getBytes(StandardCharsets.UTF_8));
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
        Log.i(TAG, "resolv.conf -> " + servers);
    }

    /**
     * Writes /etc/hosts with the loopback entries. The base Ubuntu rootfs ships
     * an empty /etc/hosts, so inside proot the name "localhost" does not resolve
     * and Node's net.connect(port, "localhost") fails, making the backend's
     * VNC port readiness checks time out.
     */
    private void writeHostsFile() throws IOException {
        String content = "127.0.0.1\tlocalhost\n"
                + "::1\t\tlocalhost ip6-localhost ip6-loopback\n"
                + "127.0.0.1\t" + hostname() + "\n";
        File hosts = new File(rootfs, "etc/hosts");
        Files.write(hosts.toPath(), content.getBytes(StandardCharsets.UTF_8));
        hosts.setReadable(true, false);
    }

    private String hostname() {
        try {
            File h = new File(rootfs, "etc/hostname");
            if (h.exists()) {
                String s = new String(Files.readAllBytes(h.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (IOException ignored) {
        }
        return "localhost";
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

    File prootBinary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    File loaderBinary() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot_loader.so");
    }

    String[] prootCommand() {
        List<String> cmd = new ArrayList<>();
        String proot = prootBinary().getAbsolutePath();
        cmd.add(proot);
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-w");
        cmd.add("/opt/a2a");

        // Replace the host /dev (which SELinux forbids apps to list) with our own
        // app-private directory, then bind the real device nodes into it by path.
        // `-b <proot>` binds the node under the same absolute path, which lands it
        // inside the private /dev (e.g. /dev/null). Missing nodes are skipped so an
        // older rootfs still starts.
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
        cmd.add("/bin/bash");
        cmd.add("/opt/a2a/start-a2a.sh");
        return cmd.toArray(new String[0]);
    }

    /**
     * proot `-b` arguments that replace the host /dev with an app-private,
     * listable directory and re-bind the individual device nodes into it.
     */
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
        // Disabling it is required for the browser to run at all here.
        env.put("MOZ_DISABLE_CONTENT_SANDBOX", "1");
        env.put("HOME", "/root");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        return env;
    }

    private void extractTar(File tarZst, File dest) throws IOException {
        // Decompress the .tar.zst with zstd-jni (bundled, seccomp-safe) and feed
        // the plain tar stream to the device's tar. The device toybox tar may not
        // support --use-compress-program, and the static zstd helper binary gets
        // killed by Android's seccomp filter (SIGSYS), so JNI decompression is
        // the reliable path.
        String tar = findTar();
        if (tar == null) {
            throw new IOException("no tar available on device");
        }
        ProcessBuilder pb = new ProcessBuilder(tar, "-xf", "-");
        pb.directory(dest);
        pb.redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw e;
        }

        // Pump decompressed bytes -> tar stdin, drain tar stderr for logging.
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
            // reports "can't link ...: Permission denied" for a couple of files and
            // exits non-zero even though the rest extracted correctly. Treat link
            // errors as non-fatal; verify the essential binaries exist instead.
            if (code != 0 && !isOnlyHardlinkErrors(err.toString())) {
                String detail = err.toString().trim();
                throw new IOException("tar=" + code + (detail.isEmpty() ? "" : " " + detail));
            }
            if (!new File(dest, "bin/bash").exists() || !new File(dest, "usr/local/bin/node").exists()) {
                throw new IOException("rootfs extraction incomplete: " + err.toString().trim());
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted");
        }
    }

    /** True when every tar error line is a hardlink-permission failure (benign on Android). */
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
