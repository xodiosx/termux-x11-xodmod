package com.termux.x11;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
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

    /* ---------- FPS STATE (like Python global) ---------- */
    private volatile String fpsValue = "N/A";
    private int frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();

    /* ---------- CPU STATE ---------- */
    private long lastIdle = 0;
    private long lastTotal = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        createOverlayView();

        startLogcatFpsThread();   // Python-like FPS attempt
        startStatsLoop();         // Periodic HUD updates
    }

    /* ===================== UI ===================== */

    private void createOverlayView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTextColor(Color.WHITE);
        hudView.setBackgroundColor(Color.argb(140, 0, 0, 0));
        hudView.setTextSize(12);
        hudView.setPadding(10, 4, 10, 4);
        hudView.setTypeface(Typeface.MONOSPACE);

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

            tickLogicalFps(); // fallback FPS

            String text =
                    "FPS: " + fpsValue + " | " +
                    getCpuTemp() + " | " +
                    getCpuUsage() + " | " +
                    getMemoryInfo();

            mainHandler.post(() -> hudView.setText(text));

        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== FPS (LOGCAT LIKE PYTHON) ===================== */

    private void startLogcatFpsThread() {
        new Thread(() -> {
            try {
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
            } catch (Exception ignored) {
                // Silent fallback to logical FPS
            }
        }, "fps-logcat-thread").start();
    }

    /* Logical FPS fallback (Tkinter-style) */
    private void tickLogicalFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            fpsValue = String.valueOf(frameCount);
            frameCount = 0;
            lastFpsTime = now;
        }
    }

    /* ===================== CPU TEMPERATURE ===================== */

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
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/stat"));
            String[] toks = br.readLine().split("\\s+");
            br.close();

            long user = Long.parseLong(toks[1]);
            long nice = Long.parseLong(toks[2]);
            long sys = Long.parseLong(toks[3]);
            long idle = Long.parseLong(toks[4]);

            long total = user + nice + sys + idle;
            long diffIdle = idle - lastIdle;
            long diffTotal = total - lastTotal;

            lastIdle = idle;
            lastTotal = total;

            if (diffTotal == 0) return "CPU: N/A";

            int usage = (int) (100 * (diffTotal - diffIdle) / diffTotal);
            return "CPU: " + usage + "%";
        } catch (Exception e) {
            return "CPU: N/A";
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