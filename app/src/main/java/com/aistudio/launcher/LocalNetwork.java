package com.aistudio.launcher;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Toggles LAN access by rewriting host / remote-management.allow-remote in
 * config.yaml, and reports the device's LAN address.
 */
final class LocalNetwork {

    private static final String TAG = "LocalNetwork";

    private LocalNetwork() {
    }

    /** Best-effort LAN IPv4 address of the device (wlan0, etc). */
    static String lanIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName();
                if (!name.startsWith("wlan") && !name.startsWith("eth")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "lanIp failed", e);
        }
        return null;
    }

    /** Returns true if LAN access is currently enabled in config.yaml. */
    static boolean isEnabled(File configFile) {
        try {
            List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            boolean remote = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("host:")) {
                    String v = t.substring(5).trim().replace("\"", "");
                    if (!v.equals("0.0.0.0")) {
                        return false;
                    }
                }
                if (t.equals("remote-management:")) {
                    for (int j = i + 1; j < lines.size(); j++) {
                        String s = lines.get(j);
                        String st = s.trim();
                        if (st.isEmpty()) continue;
                        if (indentOf(s) == 0) break;
                        if (st.startsWith("allow-remote:")) {
                            remote = st.substring("allow-remote:".length()).trim().startsWith("true");
                        }
                    }
                }
            }
            return remote;
        } catch (IOException e) {
            Log.w(TAG, "isEnabled failed", e);
            return false;
        }
    }

    /** Rewrites config.yaml to enable/disable LAN access. */
    static void setEnabled(File configFile, boolean enable) throws IOException {
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);

        // 1. host -> 0.0.0.0 / 127.0.0.1
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("host:")) {
                String indent = line.substring(0, indentOf(line));
                lines.set(i, indent + "host: \"" + (enable ? "0.0.0.0" : "127.0.0.1") + "\"");
                break;
            }
        }

        // 2. remote-management.allow-remote
        int rm = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("remote-management:")) {
                rm = i;
                break;
            }
        }
        if (rm >= 0) {
            // Find the block end: first real (non-comment) indent-0 line.
            int blockEnd = lines.size();
            for (int i = rm + 1; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                if (indentOf(lines.get(i)) == 0) {
                    blockEnd = i;
                    break;
                }
            }
            // Locate an existing allow-remote within the block.
            int allowIdx = -1;
            for (int i = rm + 1; i < blockEnd; i++) {
                if (lines.get(i).trim().startsWith("allow-remote:")) {
                    allowIdx = i;
                    break;
                }
            }
            if (allowIdx >= 0) {
                lines.set(allowIdx, "  allow-remote: " + enable);
            } else {
                lines.add(rm + 1, "  allow-remote: " + enable);
            }
        } else {
            lines.add("remote-management:");
            lines.add("  allow-remote: " + enable);
        }

        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        Files.write(configFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
