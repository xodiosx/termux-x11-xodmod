package com.termux.x11;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.*;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HudService extends Service {

    private static final String TAG = "HudService";
    private static final String CHANNEL_ID = "hud_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private TextView hudView;

    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    /* ================= FPS ================= */
    private volatile String fpsValue = "FPS: ...";
    private Thread fpsThread;
    private volatile boolean fpsRunning;

    private static final Pattern FPS_PATTERN =
            Pattern.compile("=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*FPS");

    /* ================= CPU ================= */
    private long lastIdle = -1;
    private long lastTotal = -1;

    /* ================= GPU ================= */
    private String gpuName = "GPU: N/A";

    /* ================= MEMORY ================= */
    private String totalRam;

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        gpuName = detectGpuName();
        totalRam = getTotalRAM();

        createOverlay();
        startFpsReader();
        startHudUpdater();
    }

    /* ================= OVERLAY ================= */

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTypeface(Typeface.MONOSPACE);
        hudView.setTextSize(12);
        hudView.setPadding(12, 6, 12, 6);
        hudView.setBackgroundColor(Color.argb(160, 0, 0, 0));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );

        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 5;
        p.y = 5;

        windowManager.addView(hudView, p);
    }

    /* ================= HUD LOOP ================= */

    private void startHudUpdater() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            SpannableString s = buildHudText();
            mainHandler.post(() -> hudView.setText(s));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private SpannableString buildHudText() {
        String fps = fpsValue;
        String temp = getCpuTemp();
        String cpu = getCpuUsage();
        String gpu = gpuName;
        String mem = getMemoryInfo();

        String text = fps + " | " + temp + " | " + cpu + " | " + gpu + " | " + mem;
        SpannableString ss = new SpannableString(text);

        color(ss, fps, Color.GREEN);
        color(ss, temp, Color.CYAN);
        color(ss, cpu, Color.YELLOW);
        color(ss, gpu, Color.MAGENTA);
        color(ss, mem, Color.LTGRAY);

        return ss;
    }

    private void color(SpannableString s, String part, int color) {
        int i = s.toString().indexOf(part);
        if (i >= 0) {
            s.setSpan(new ForegroundColorSpan(color),
                    i, i + part.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /* ================= FPS READER ================= */

    private void startFpsReader() {
        fpsRunning = true;

        fpsThread = new Thread(() -> {
            BufferedReader reader = null;
            Process proc = null;

            try {
                String cmd =
                        "logcat -v brief | grep --line-buffered \"LorieNative:.*FPS\"";

                proc = new ProcessBuilder("sh", "-c", cmd)
                        .redirectErrorStream(true)
                        .start();

                reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));

                String line;
                while (fpsRunning && (line = reader.readLine()) != null) {

                    Matcher m = FPS_PATTERN.matcher(line);
                    if (m.find()) {
                        fpsValue = "FPS: " + m.group(1);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "FPS reader failed", e);
                fpsValue = "FPS: ERR";
            } finally {
                try { if (reader != null) reader.close(); } catch (IOException ignored) {}
                if (proc != null) proc.destroy();
            }
        }, "FPS-Reader");

        fpsThread.setDaemon(true);
        fpsThread.start();
    }

    /* ================= CPU ================= */

    private String getCpuTemp() {
        for (int i = 0; i < 10; i++) {
            try (BufferedReader br = new BufferedReader(
                    new FileReader("/sys/class/thermal/thermal_zone" + i + "/temp"))) {

                int t = Integer.parseInt(br.readLine().trim());
                if (t > 10000) {
                    return String.format(Locale.US,
                            "CPU: %.1f°C", t / 1000f);
                }
            } catch (Exception ignored) {}
        }
        return "CPU: N/A";
    }

    private String getCpuUsage() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {

            String[] s = br.readLine().split("\\s+");
            long idle = Long.parseLong(s[4]);
            long total = 0;

            for (int i = 1; i < s.length; i++)
                total += Long.parseLong(s[i]);

            if (lastTotal < 0) {
                lastIdle = idle;
                lastTotal = total;
                return "CPU: ...";
            }

            long dt = total - lastTotal;
            long di = idle - lastIdle;

            lastTotal = total;
            lastIdle = idle;

            return String.format(Locale.US,
                    "CPU: %.1f%%", (dt - di) * 100f / dt);

        } catch (Exception e) {
            lastTotal = lastIdle = -1;
            return "CPU: N/A";
        }
    }

    /* ================= GPU ================= */

    private String detectGpuName() {
        String[] props = {
                "ro.hardware.vulkan",
                "ro.hardware.egl",
                "ro.board.platform"
        };

        for (String p : props) {
            String v = getProp(p);
            if (v != null && !v.isEmpty())
                return "GPU: " + v;
        }
        return "GPU: Unknown";
    }

    private String getProp(String k) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", k});
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /* ================= MEMORY ================= */

    private String getTotalRAM() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE))
                .getMemoryInfo(mi);
        return formatBytes(mi.totalMem);
    }

    private String getMemoryInfo() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE))
                .getMemoryInfo(mi);

        long used = mi.totalMem - mi.availMem;
        return "MEM: " + formatBytes(used) + " / " + totalRam;
    }

    private String formatBytes(long b) {
        int u = 0;
        double d = b;
        while (d > 1024) { d /= 1024; u++; }
        return String.format(Locale.US, "%.1f %cB", d, "KMGTPE".charAt(u));
    }

    /* ================= NOTIFICATION ================= */

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("HUD active")
                .setContentText("Performance overlay running")
                .setSmallIcon(android.R.drawable.presence_online)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "HUD",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        fpsRunning = false;
        if (fpsThread != null) fpsThread.interrupt();
        if (scheduler != null) scheduler.shutdownNow();
        if (hudView != null) windowManager.removeView(hudView);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}