package com.termux.x11;

import java.util.Locale;
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
    private volatile String fpsValue = "FPS: N/A";
    private Thread fpsReaderThread;
    private volatile boolean fpsReaderRunning = true;

    /* ---------- CPU STATE ---------- */
    private long lastIdle = -1;
    private long lastTotal = -1;

    /* ---------- GPU ---------- */
    private String gpuName = "GPU: N/A";

    /* ---------- MEMORY ---------- */
    private String totalRAM = null;

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        gpuName = detectGpuName();
        totalRAM = getTotalRAM();

        createOverlayView();

        startFpsReaderThread();      // new method replacing old logcat thread
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

            SpannableString hudText = buildColoredHud();

            mainHandler.post(() -> hudView.setText(hudText));

        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== HUD TEXT ===================== */

    private SpannableString buildColoredHud() {

        String fps = fpsValue;   // already includes "FPS: " prefix
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

    /* ===================== FPS READER (based on LorieNative logs) ===================== */

    private void startFpsReaderThread() {
    fpsReaderRunning = true;
    fpsReaderThread = new Thread(() -> {
        java.lang.Process process = null;
        BufferedReader reader = null;
        try {
            // Use ProcessBuilder to merge error stream and avoid blocking
            ProcessBuilder pb = new ProcessBuilder("logcat", "-s", "LorieNative:I", "-v", "brief");
            pb.redirectErrorStream(true); // combine stderr into stdout
            process = pb.start();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while (fpsReaderRunning && (line = reader.readLine()) != null) {
                // Example: "02-16 17:45:28.796 I LorieNative: 34 frames in 5.0 seconds = 6.8 FPS"
                if (line.contains("LorieNative") && line.contains("FPS")) {
                    int idx = line.lastIndexOf('=');
                    if (idx != -1) {
                        String afterEq = line.substring(idx + 1).trim();
                        String[] parts = afterEq.split("\\s+");
                        if (parts.length > 0) {
                            fpsValue = "FPS: " + parts[0];
                        }
                    }
                }
            }
        } catch (Exception e) {
            fpsValue = "FPS: error";
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
            if (process != null) process.destroy();
        }
    }, "FPS-Logcat-Reader");
    fpsReaderThread.setDaemon(true);
    fpsReaderThread.start();
}

    // Compatibility: keep old method name if called elsewhere, but redirect to new one
    private void startLogcatFpsThread() {
        startFpsReaderThread();
    }

    // The old tickLogicalFps is no longer needed – we keep it as a no-op to avoid breaking existing calls
    private void tickLogicalFps() {
        // Do nothing – FPS now comes from logcat reader
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

    /* ===================== CPU USAGE ===================== */

    private String getCpuUsage() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return "CPU: N/A";

            String[] tokens = line.split("\\s+");

            long idle = Long.parseLong(tokens[4]);
            long total = 0;
            for (int i = 1; i < tokens.length; i++) {
                total += Long.parseLong(tokens[i]);
            }

            if (lastTotal < 0) {
                lastTotal = total;
                lastIdle = idle;
                return "CPU: ...";
            }

            long dTotal = total - lastTotal;
            long dIdle = idle - lastIdle;

            lastTotal = total;
            lastIdle = idle;

            if (dTotal <= 0) {
                return "CPU: 0%";
            }

            float usage = (dTotal - dIdle) * 100f / dTotal;
            return String.format("CPU: %.1f%%", usage);

        } catch (Exception e) {
            lastTotal = -1;
            lastIdle = -1;
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
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /* ===================== MEMORY ===================== */

    private String getTotalRAM() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return formatBytes(memoryInfo.totalMem);
    }

    private String getAvailableRAM() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        return formatBytes(usedMem);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String getMemoryInfo() {
        if (totalRAM == null) totalRAM = getTotalRAM();
        return "MEM: " + getAvailableRAM() + " / " + totalRAM;
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
        fpsReaderRunning = false; // stop the reader thread
        if (fpsReaderThread != null) fpsReaderThread.interrupt();
        if (hudView != null) windowManager.removeView(hudView);
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}