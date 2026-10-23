package com.aistudio2api.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands inside the embedded PRoot Ubuntu rootfs and streams their output
 * line by line.
 *
 * Used for one-shot maintenance (the Go backend's {@code setup --login}) and for
 * long-lived helper processes (the Xvnc + websockify login stack).
 */
public final class ProotRunner {

    private static final String TAG = "ProotRunner";

    public interface OutputListener {
        void onLine(String line);
    }

    public static final class Result {
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

    /** A running process inside the rootfs that can be stopped later. */
    public static final class Session {
        final Process process;
        final Thread pump;
        private final List<String> lines = new ArrayList<>();

        Session(Process process, Thread pump) {
            this.process = process;
            this.pump = pump;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public List<String> lines() {
            synchronized (lines) {
                return new ArrayList<>(lines);
            }
        }

        public void destroy() {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private ProotRunner() {
    }

    /** Starts a long-lived process; output is streamed to {@code listener}. */
    public static Session start(Context ctx, RuntimeEnv env, OutputListener listener,
                                String... commandInsideRootfs) throws IOException {
        ProcessBuilder pb = build(ctx, env, commandInsideRootfs);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 proot: " + e.getMessage(), e);
        }
        Session session = new Session(p, null);
        Thread pump = new Thread(() -> drain(p, listener, session.lines), "proot-out");
        pump.setDaemon(true);
        pump.start();
        return session;
    }

    /** Runs a command to completion (blocking; call from a worker thread). */
    public static Result run(Context ctx, RuntimeEnv env, long timeoutMs, OutputListener listener,
                             String... commandInsideRootfs) throws IOException {
        ProcessBuilder pb = build(ctx, env, commandInsideRootfs);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 proot: " + e.getMessage(), e);
        }
        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
                if (listener != null) {
                    listener.onLine(line);
                }
                if (timeoutMs > 0 && System.currentTimeMillis() > deadline) {
                    p.destroyForcibly();
                    if (listener != null) {
                        listener.onLine("[TIMEOUT] 命令超时 (" + (timeoutMs / 1000) + "s) 已终止");
                    }
                    return new Result(-1, lines);
                }
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

    private static ProcessBuilder build(Context ctx, RuntimeEnv env, String... commandInsideRootfs) {
        List<String> cmd = new ArrayList<>();
        for (String a : env.prootCommandFor(commandInsideRootfs)) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(env.prootDir());
        pb.redirectErrorStream(true);
        Map<String, String> extra = env.prootEnv();
        pb.environment().putAll(extra);
        Log.i(TAG, "proot exec: " + commandInsideRootfs[0]);
        return pb;
    }

    private static void drain(Process p, OutputListener listener, List<String> sink) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (sink) {
                    if (sink.size() > 500) {
                        sink.remove(0);
                    }
                    sink.add(line);
                }
                if (listener != null) {
                    listener.onLine(line);
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Lists account directories created by the Go backend inside the rootfs. */
    public static File[] listAccounts(Context ctx) {
        File dir = new RuntimeEnv(ctx).authDir();
        File[] files = dir.listFiles(File::isDirectory);
        return files != null ? files : new File[0];
    }
}
