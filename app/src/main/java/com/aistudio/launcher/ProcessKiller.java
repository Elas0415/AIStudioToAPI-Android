package com.aistudio.launcher;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Kills orphaned proot/node/camoufox processes owned by this app.
 *
 * Android's toybox `ps -o PID,NAME,USER` does not expose a numeric UID column
 * (and `ps -o UID` is silently ignored), so the previous numeric-UID match never
 * fired and stale proot/node/camoufox processes kept port 7860/5901/6080 busy
 * after a service restart. We instead filter by this app's own process owner and
 * match the command/name prefixes we care about.
 */
final class ProcessKiller {

    private static final String TAG = "ProcessKiller";

    private static final String[] NAMES = {
            "node", "camoufox", "camoufox-bin", "Xvfb", "x11vnc", "websockify",
            "bash", "libproot.so",
    };

    private ProcessKiller() {
    }

    static void killBackendProcesses() {
        Set<Integer> pids = collectPids();
        int self = android.os.Process.myPid();
        for (int pid : pids) {
            if (pid == self) {
                continue;
            }
            try {
                android.system.Os.kill(pid, android.system.OsConstants.SIGKILL);
                Log.i(TAG, "killed pid " + pid);
            } catch (Exception e) {
                Log.w(TAG, "kill " + pid + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * Finds PIDs owned by this app whose NAME matches one of {@link #NAMES}.
     * `ps -e -o PID,USER,NAME` is parsed by columns, skipping the header line.
     */
    private static Set<Integer> collectPids() {
        Set<Integer> pids = new LinkedHashSet<>();
        String owner = processOwner();
        try {
            java.lang.Process p = new ProcessBuilder(
                    "sh", "-c", "ps -e -o PID,USER,NAME 2>/dev/null")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean header = true;
                while ((line = r.readLine()) != null) {
                    if (header) {
                        header = false;
                        if (line.trim().startsWith("PID")) {
                            continue;
                        }
                    }
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    // PID USER NAME...
                    String[] cols = line.split("\\s+", 3);
                    if (cols.length < 3) {
                        continue;
                    }
                    if (owner != null && !owner.equals(cols[1])) {
                        continue;
                    }
                    if (matchesName(cols[2])) {
                        try {
                            pids.add(Integer.parseInt(cols[0]));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "scan processes failed", e);
        }
        return pids;
    }

    /** This app's process owner (Linux user name) as reported by `ps USER`. */
    private static String processOwner() {
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", "id -un")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String name = r.readLine();
                p.waitFor();
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean matchesName(String name) {
        String base = name;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        for (String n : NAMES) {
            if (base.equals(n) || base.startsWith(n)) {
                return true;
            }
        }
        return false;
    }
}
