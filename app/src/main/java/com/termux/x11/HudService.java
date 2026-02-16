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
import java.util.regex.*;

public class HudService extends Service {

    private static final String CHANNEL_ID = "hud_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private TextView hudView;

    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    /* ================= FPS (logcat LorieNative) ================= */

    private volatile String fpsValue = "FPS: N/A";

    private void startFpsLogcatReader() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("logcat");
                BufferedReader br =
                        new BufferedReader(new InputStreamReader(p.getInputStream()));

                Pattern fpsPattern =
                        Pattern.compile("=\\s*([0-9]+(\\.[0-9]+)?)\\s*FPS");

                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("LorieNative") && line.contains("FPS")) {
                        Matcher m = fpsPattern.matcher(line);
                        if (m.find()) {
                            fpsValue = "FPS: " + m.group(1);
                        }
                    }
                }
            } catch (Exception e) {
                fpsValue = "FPS: error";
            }
        }, "fps-reader").start();
    }

    /* ================= CPU USAGE ================= */

    private long lastIdle = 0, lastTotal = 0;

    private String getCpuUsage() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] t = br.readLine().split("\\s+");

            long idle = Long.parseLong(t[4]);
            long total = 0;
            for (int i = 1; i < t.length; i++) total += Long.parseLong(t[i]);

            if (lastTotal == 0) {
                lastTotal = total;
                lastIdle = idle;
                return "CPU: ...";
            }

            float usage =
                    (total - lastTotal - (idle - lastIdle)) * 100f / (total - lastTotal);

            lastTotal = total;
            lastIdle = idle;

            return String.format("CPU: %.1f%%", usage);
        } catch (Exception e) {
            return "CPU: N/A";
        }
    }

    /* ================= CPU TEMP ================= */

    private String getCpuTemp() {
        for (int i = 0; i < 10; i++) {
            try {
                File f = new File("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (!f.exists()) continue;

                BufferedReader br = new BufferedReader(new FileReader(f));
                int t = Integer.parseInt(br.readLine().trim());
                br.close();

                if (t > 10000) return String.format("CPU: %.1f°C", t / 1000f);
            } catch (Exception ignored) {}
        }
        return "CPU: N/A";
    }

    /* ================= MEMORY ================= */

    private String getMemory() {
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
            return String.format("MEM: %dMB / %dMB",
                    (total - avail) / 1024, total / 1024);
        } catch (Exception e) {
            return "MEM: N/A";
        }
    }

    /* ================= SERVICE ================= */

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        createOverlay();
        startFpsLogcatReader();
        startUpdater();
    }

    private void startUpdater() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            String text =
                    fpsValue + "\n" +
                    getCpuUsage() + " | " + getCpuTemp() + "\n" +
                    getMemory();

            mainHandler.post(() -> hudView.setText(text));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTypeface(Typeface.MONOSPACE);
        hudView.setTextSize(12);
        hudView.setTextColor(Color.CYAN);
        hudView.setBackgroundColor(Color.argb(150, 0, 0, 0));
        hudView.setPadding(12, 6, 12, 6);

        WindowManager.LayoutParams p =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 8;
        p.y = 8;

        windowManager.addView(hudView, p);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("HUD running")
                .setSmallIcon(android.R.drawable.presence_online)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch =
                    new NotificationChannel(CHANNEL_ID,
                            "HUD",
                            NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(ch);
        }
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