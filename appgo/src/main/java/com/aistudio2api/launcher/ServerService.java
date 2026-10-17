package com.aistudio2api.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that runs the Go backend {@code Mag1cFall/AIStudio2API}
 * inside an embedded PRoot + Ubuntu 24.04 ARM64 rootfs.
 *
 * The binary serves its management WebUI and the OpenAI/Responses/Anthropic/
 * Gemini compatible API on 127.0.0.1:2048 (or 0.0.0.0:2048 with LAN enabled).
 */
public class ServerService extends Service {

    static final String TAG = "AIStudio2APIServer";
    static final int NOTIFICATION_ID = 2001;

    static final String ACTION_START = "com.aistudio2api.launcher.START";
    static final String ACTION_RESTART = "com.aistudio2api.launcher.RESTART";
    static final String ACTION_STOP = "com.aistudio2api.launcher.STOP";

    static final String BROADCAST_STATE = "com.aistudio2api.launcher.STATE";
    static final String EXTRA_RUNNING = "running";

    static final int MAX_CONSECUTIVE_CRASHES = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Process process;
    private volatile ProotRunner.Session displaySession;
    private int crashCount;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        startForeground(NOTIFICATION_ID, buildNotification("正在启动..."));

        if (ACTION_STOP.equals(action)) {
            worker.execute(this::stopBackendAndUpdate);
            worker.execute(this::stopSelf);
            return START_NOT_STICKY;
        }

        if (ACTION_RESTART.equals(action)) {
            worker.execute(() -> {
                crashCount = 0;
                stopBackendAndUpdate();
                sleep(700);
                startBackend();
            });
            return START_STICKY;
        }

        worker.execute(this::startBackend);
        return START_STICKY;
    }

    private synchronized void startBackend() {
        if (running.get() && process != null && process.isAlive()) {
            return;
        }
        try {
            RuntimeEnv env = new RuntimeEnv(this);
            env.ensureReady();

            String apiKey = env.ensureAppConfig(env.isLanEnabled());
            env.writeEnvFile(apiKey, env.isLanEnabled());

            ProcessKiller.killBackendProcesses();
            sleep(400);

            // The backend opens a *visible* Camoufox when the management UI's
            // 「浏览器登录」 button creates an account, so the virtual desktop it
            // draws on has to be up first.
            startDisplay();

            ProcessBuilder pb = new ProcessBuilder(env.prootCommand());
            pb.directory(env.prootDir());
            pb.redirectErrorStream(true);
            pb.environment().putAll(env.prootEnv());

            Process proc = pb.start();
            process = proc;
            running.set(true);
            Log.i(TAG, "aistudio2api backend started via proot");
            updateNotification("本地服务已启动 (端口 " + RuntimeEnv.PORT + ")");
            notifyState(true);

            Thread monitor = new Thread(() -> monitorProcess(proc), "a2api-monitor");
            monitor.setDaemon(true);
            monitor.start();
        } catch (Exception e) {
            Log.e(TAG, "failed to launch backend", e);
            running.set(false);
            updateNotification("启动失败: " + e.getMessage());
            notifyState(false);
        }
    }

    /**
     * Brings up Xvfb :99 + x11vnc + websockify/noVNC so that a headful Camoufox
     * opened by the backend has somewhere to render. Best effort: a failure only
     * means the login window will not be displayable, not that the service dies.
     */
    private synchronized void startDisplay() {
        if (displaySession != null && displaySession.isAlive()) {
            return;
        }
        stopDisplay();
        try {
            RuntimeEnv env = new RuntimeEnv(this);
            displaySession = ProotRunner.start(this, env,
                    line -> Log.i(TAG, "[vnc] " + line),
                    RuntimeEnv.vncServeCommandInsideRootfs());
            boolean ready = waitForDisplay(12_000);
            Log.i(TAG, ready
                    ? "virtual desktop ready on 127.0.0.1:" + RuntimeEnv.NOVNC_PORT
                    : "virtual desktop did not open port " + RuntimeEnv.NOVNC_PORT);
        } catch (Exception e) {
            Log.e(TAG, "failed to start virtual desktop", e);
            displaySession = null;
        }
    }

    private synchronized void stopDisplay() {
        ProotRunner.Session session = displaySession;
        displaySession = null;
        if (session != null) {
            session.destroy();
        }
    }

    /** Waits for websockify to accept connections, giving up early if it died. */
    private boolean waitForDisplay(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ProotRunner.Session session = displaySession;
            if (session == null) {
                return false;
            }
            if (!session.isAlive()) {
                Log.w(TAG, "virtual desktop exited early: " + session.lines());
                return false;
            }
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(
                        "127.0.0.1", RuntimeEnv.NOVNC_PORT), 400);
                return true;
            } catch (IOException ignored) {
            }
            sleep(400);
        }
        return false;
    }

    private void monitorProcess(Process proc) {        pipeLogs(proc);
        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Log.i(TAG, "backend exited code=" + code);
        synchronized (this) {
            if (process == proc) {
                process = null;
                running.set(false);
                notifyState(false);
                if (code == 0) {
                    updateNotification("服务已停止");
                    return;
                }
                crashCount++;
                if (crashCount <= MAX_CONSECUTIVE_CRASHES) {
                    updateNotification("服务崩溃 (code " + code + ")，第 "
                            + crashCount + " 次自动重启…");
                    sleep(2000L * crashCount);
                    if (!running.get()) {
                        startBackend();
                    }
                } else {
                    updateNotification("服务连续崩溃 " + crashCount + " 次，已停止自动重启，请查看日志");
                }
            }
        }
    }

    private synchronized void stopBackendAndUpdate() {
        Process proc = process;
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            try {
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            process = null;
        }
        ProcessKiller.killBackendProcesses();
        stopDisplay();
        running.set(false);
        notifyState(false);
        updateNotification("服务已停止");
    }

    private void pipeLogs(Process p) {
        File logFile = new File(new RuntimeEnv(this).logsDir(), "aistudio2api.log");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
             OutputStream out = new FileOutputStream(logFile, true)) {
            String line;
            byte[] nl = "\n".getBytes("UTF-8");
            while ((line = r.readLine()) != null) {
                Log.i(TAG, "[a2api] " + line);
                out.write(line.getBytes("UTF-8"));
                out.write(nl);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private void notifyState(boolean isRunning) {
        Intent i = new Intent(BROADCAST_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING, isRunning);
        sendBroadcast(i);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private Notification buildNotification(String text) {
        String channelId = "a2api_status";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    channelId,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_icon)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        stopBackendAndUpdate();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
