package com.aistudio.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an arbitrary command inside the embedded PRoot Ubuntu rootfs and
 * streams its output line by line.
 *
 * Used by the headless Google login flow (saveAuth.js) and other one-shot
 * maintenance tasks. The main API server itself is started by ServerService.
 */
public final class ProotRunner {

    public interface OutputListener {
        void onLine(String line);
    }

    public static class Result {
        public final int exitCode;
        public final List<String> lines;

        Result(int exitCode, List<String> lines) {
            this.exitCode = exitCode;
            this.lines = lines;
        }

        public boolean ok() {
            return exitCode == 0;
        }

        public String joined() {
            return String.join("\n", lines);
        }
    }

    private static final String TAG = "ProotRunner";

    private ProotRunner() {
    }

    /**
     * Builds the proot command prefix for the given working dir inside rootfs.
     * Mirrors RuntimeEnv.prootCommand() but with a custom workdir and command.
     */
    private static List<String> buildCommand(Context ctx, RuntimeEnv env, String workdirInsideRootfs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(env.prootBinary().getAbsolutePath());
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(env.rootfsPath());
        cmd.add("-w");
        cmd.add(workdirInsideRootfs);
        for (String bind : env.devBinds()) {
            cmd.add("-b");
            cmd.add(bind);
        }
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(ctx.getCacheDir().getAbsolutePath() + ":/sdcard-cache");
        return cmd;
    }

    private static Map<String, String> buildEnv(RuntimeEnv env) {
        Map<String, String> e = env.prootEnv();
        return e != null ? e : new HashMap<>();
    }

    /**
     * Runs a command inside the rootfs. Blocking; call from a worker thread.
     *
     * @param commandInsideRootfs argv executed inside proot, e.g.
     *                             ["/usr/local/bin/node", "scripts/auth/saveAuth.js", "--help"]
     * @param timeoutMs            hard timeout in milliseconds
     */
    public static Result run(Context ctx, String[] commandInsideRootfs, long timeoutMs, OutputListener listener)
            throws IOException {
        RuntimeEnv env = new RuntimeEnv(ctx);
        return run(ctx, env, "/opt/a2a", commandInsideRootfs, timeoutMs, listener);
    }

    public static Result run(Context ctx, RuntimeEnv env, String workdirInsideRootfs,
                             String[] commandInsideRootfs, long timeoutMs, OutputListener listener)
            throws IOException {
        return run(ctx, env, workdirInsideRootfs, commandInsideRootfs, timeoutMs, listener, null);
    }

    /**
     * Same as above but with extra environment variables for the child process
     * (e.g. CAMOUFOX_EXECUTABLE_PATH for the auth scripts).
     */
    public static Result run(Context ctx, RuntimeEnv env, String workdirInsideRootfs,
                             String[] commandInsideRootfs, long timeoutMs, OutputListener listener,
                             Map<String, String> extraEnv)
            throws IOException {
        List<String> cmd = buildCommand(ctx, env, workdirInsideRootfs);
        for (String a : commandInsideRootfs) {
            cmd.add(a);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(env.prootDir());
        pb.redirectErrorStream(true);
        pb.environment().putAll(buildEnv(env));
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }

        Log.i(TAG, "proot exec: " + commandInsideRootfs[0] + " ... (" + commandInsideRootfs.length + " args)");

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 proot: " + e.getMessage(), e);
        }
        setActiveProcess(p);

        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    p.destroyForcibly();
                    throw new IOException("interrupted");
                }
                line = r.readLine();
                if (line == null) {
                    break;
                }
                lines.add(line);
                if (listener != null) {
                    listener.onLine(line);
                }
                if (System.currentTimeMillis() > deadline) {
                    p.destroyForcibly();
                    if (listener != null) {
                        listener.onLine("[TIMEOUT] 命令超时 (" + (timeoutMs / 1000) + "s) 已终止");
                    }
                    return new Result(-1, lines);
                }
            }
            int code;
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    code = -1;
                } else {
                    code = p.exitValue();
                }
            } catch (InterruptedException ie) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                code = -1;
            }
            return new Result(code, lines);
        }
    }

    /** True if a login (saveAuth) process is currently running. */
    public static synchronized boolean isLoginRunning() {
        return loginRunning;
    }

    /** Sets the global login-in-progress flag so UI can avoid double launching. */
    public static synchronized void setLoginRunning(boolean v) {
        loginRunning = v;
        if (!v) {
            activeProcess = null;
        }
    }

    /** Registers the process currently owned by the login flow so it can be cancelled. */
    static synchronized void setActiveProcess(Process p) {
        activeProcess = p;
    }

    /** Force-kills the active login process, if any. */
    public static synchronized void cancelActive() {
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        activeProcess = null;
        loginRunning = false;
    }

    private static boolean loginRunning = false;
    private static volatile Process activeProcess;

    /** Lists existing auth-N.json files inside the rootfs. */
    public static File[] listAuthFiles(Context ctx) {
        RuntimeEnv env = new RuntimeEnv(ctx);
        File dir = env.authDir();
        File[] files = dir.listFiles((d, name) -> name.matches("auth-\\d+\\.json"));
        return files != null ? files : new File[0];
    }
}
