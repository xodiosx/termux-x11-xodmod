package com.termux.x11;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.io.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HudService extends Service {

    private static final String CHANNEL_ID = "hud_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private TextView hudView;

    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    /* ---------- FPS STATE ---------- */
    private volatile String fpsValue = "N/A";
    private int frameCount = 0;               // kept only for compatibility, not used for display
    private long lastFpsTime = System.currentTimeMillis();

    /* ---------- CPU STATE ---------- */
    private long lastIdle = -1;
    private long lastTotal = -1;

    /* ---------- GPU ---------- */
    private String gpuName = "GPU: N/A";

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        gpuName = detectGpuName();

        createOverlayView();

        startLogcatFpsThread();
        startStatsLoop();
    }

    /* ===================== UI ===================== */

    private void createOverlayView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTextSize(12);
        hudView.setPadding(10, 4, 10, 4);
        hudView.setTypeface(Typeface.MONOSPACE);
        hudView.setBackgroundColor(Color.argb(140, 0, 0, 0));

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 5;
        params.y = 0;

        windowManager.addView(hudView, params);
    }

    /* ===================== MAIN LOOP ===================== */

    private void startStatsLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {

            // tickLogicalFps() is called only to keep its side‑effects unchanged,
            // but it no longer overwrites the real fpsValue.
            tickLogicalFps();

            SpannableString hudText = buildColoredHud();

            mainHandler.post(() -> hudView.setText(hudText));

        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== HUD TEXT ===================== */

    private SpannableString buildColoredHud() {

        String fps = "FPS: " + fpsValue;
        String temp = getCpuTemp();
        String cpu = getCpuUsage();
        String mem = getMemoryInfo();
        String gpu = gpuName;

        String full =
                fps + " | " +
                temp + " | " +
                cpu + " | " +
                gpu + " | " +
                mem;

        SpannableString s = new SpannableString(full);

        colorPart(s, fps, Color.YELLOW);
        colorPart(s, temp, Color.CYAN);
        colorPart(s, cpu, Color.GREEN);
        colorPart(s, gpu, Color.MAGENTA);
        colorPart(s, mem, Color.LTGRAY);

        return s;
    }

    private void colorPart(SpannableString s, String part, int color) {
        int start = s.toString().indexOf(part);
        if (start >= 0) {
            s.setSpan(
                    new ForegroundColorSpan(color),
                    start,
                    start + part.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
    }

    /* ===================== FPS (FIXED) ===================== */

    private void startLogcatFpsThread() {
        new Thread(() -> {
            try {
                // Use a filtered logcat command to reduce noise (optional)
                Process p = Runtime.getRuntime().exec("logcat");
                BufferedReader br =
                        new BufferedReader(new InputStreamReader(p.getInputStream()));

                Pattern pattern = Pattern.compile("(\\d+(\\.\\d+)?)\\s*FPS");

                String line;
                while ((line = br.readLine()) != null) {
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        fpsValue = m.group(1);
                    }
                }
            } catch (Exception ignored) {}
        }, "fps-logcat-thread").start();
    }

    // This method is kept only because it is called from startStatsLoop().
    // It no longer interferes with the real FPS value.
    private void tickLogicalFps() {
        // Increment frame counter (unused) – purely to preserve original structure.
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            // Do NOT update fpsValue here – it is now only set by the logcat thread.
            frameCount = 0;
            lastFpsTime = now;
        }
    }

    /* ===================== CPU TEMP ===================== */

    private String getCpuTemp() {
        for (int i = 0; i < 10; i++) {
            try {
                String path = "/sys/class/thermal/thermal_zone" + i + "/temp";
                BufferedReader br = new BufferedReader(new FileReader(path));
                int temp = Integer.parseInt(br.readLine().trim());
                br.close();

                if (temp > 10000) {
                    return String.format("CPU: %.1f°C", temp / 1000f);
                }
            } catch (Exception ignored) {}
        }
        return "CPU: N/A";
    }

    /* ===================== CPU USAGE (FIXED) ===================== */

    private String getCpuUsage() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return "CPU: N/A";

            String[] tokens = line.split("\\s+");

            // Idle is the 4th field (index 4 after "cpu")
            long idle = Long.parseLong(tokens[4]);
            long total = 0;
            for (int i = 1; i < tokens.length; i++) {
                total += Long.parseLong(tokens[i]);
            }

            if (lastTotal < 0) {
                // First sample – store values and return placeholder
                lastTotal = total;
                lastIdle = idle;
                return "CPU: ...";
            }

            long dTotal = total - lastTotal;
            long dIdle = idle - lastIdle;

            lastTotal = total;
            lastIdle = idle;

            // Avoid division by zero or negative differences
            if (dTotal <= 0) {
                return "CPU: 0%";
            }

            float usage = (dTotal - dIdle) * 100f / dTotal;
            return String.format("CPU: %.1f%%", usage);

        } catch (NumberFormatException e) {
            // One of the fields was not a number – unlikely but handled
            return "CPU: N/A";
        } catch (Exception e) {
            // File not readable, etc.
            return "CPU: N/A";
        }
    }

    /* ===================== GPU NAME ===================== */

    private String detectGpuName() {
        String[] props = {
                "ro.hardware.vulkan",
                "ro.hardware.egl",
                "ro.board.platform"
        };

        for (String p : props) {
            String v = getProp(p);
            if (v != null && !v.isEmpty()) {
                return "GPU: " + v;
            }
        }
        return "GPU: Unknown";
    }

    private String getProp(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /* ===================== MEMORY ===================== */

    private String getMemoryInfo() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
            int total = 0, avail = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:"))
                    total = Integer.parseInt(line.split("\\s+")[1]);
                else if (line.startsWith("MemAvailable:"))
                    avail = Integer.parseInt(line.split("\\s+")[1]);
            }
            br.close();

            return String.format(
                    "MEM: %dMB / %dMB",
                    (total - avail) / 1024,
                    total / 1024
            );
        } catch (Exception e) {
            return "MEM: N/A";
        }
    }

    /* ===================== NOTIFICATION ===================== */

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "HUD Service",
                            NotificationManager.IMPORTANCE_LOW
                    );
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("HUD active")
                .setContentText("System monitor overlay running")
                .setSmallIcon(android.R.drawable.presence_online)
                .build();
    }

    @Override
    public void onDestroy() {
        if (hudView != null) windowManager.removeView(hudView);
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}