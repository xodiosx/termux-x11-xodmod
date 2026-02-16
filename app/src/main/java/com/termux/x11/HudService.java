package com.termux.x11;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.opengl.GLES20;
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

    /* ================= FPS (from LorieNative logcat) ================= */

    private volatile String fpsValue = "FPS: N/A";

    private void startFpsLogcatReader() {
        new Thread(() -> {
            try {
                java.lang.Process process =
                        Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief"});

                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));

                Pattern fpsPattern =
                        Pattern.compile("=\\s*([0-9]+(\\.[0-9]+)?)\\s*FPS");

                String line;
                while ((line = reader.readLine()) != null) {
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
        }, "fps-logcat-thread").start();
    }

    /* ================= CPU USAGE ================= */

    private long lastIdle = 0;
    private long lastTotal = 0;

    private String getCpuUsage() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                return "CPU: N/A";
            }

            String[] t = line.split("\\s+");
            long user = Long.parseLong(t[1]);
            long nice = Long.parseLong(t[2]);
            long system = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long softirq = t.length > 7 ? Long.parseLong(t[7]) : 0;

            long idleTime = idle + iowait;
            long totalTime = user + nice + system + idle + iowait + irq + softirq;

            if (lastTotal == 0) {
                lastTotal = totalTime;
                lastIdle = idleTime;
                return "CPU: ...";
            }

            long deltaTotal = totalTime - lastTotal;
            long deltaIdle = idleTime - lastIdle;

            lastTotal = totalTime;
            lastIdle = idleTime;

            float usage = (deltaTotal - deltaIdle) * 100f / deltaTotal;
            return String.format("CPU: %.1f%%", usage);

        } catch (Exception e) {
            return "CPU: N/A";
        }
    }

    /* ================= CPU TEMPERATURE (UNCHANGED) ================= */

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

    /* ================= MEMORY ================= */

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

    /* ================= GPU NAME (CPU-Z STYLE) ================= */

    private String getGpuName() {
        try {
            return "GPU: " + GLES20.glGetString(GLES20.GL_RENDERER);
        } catch (Exception e) {
            return "GPU: N/A";
        }
    }

    /* ================= SERVICE LIFECYCLE ================= */

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        createOverlayView();
        startFpsLogcatReader();
        startStatsLoop();
    }

    private void startStatsLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {

            String text =
                    fpsValue + "\n" +
                    getCpuUsage() + " | " + getCpuTemp() + "\n" +
                    getGpuName() + "\n" +
                    getMemoryInfo();

            mainHandler.post(() -> hudView.setText(text));

        }, 0, 1, TimeUnit.SECONDS);
    }

    private void createOverlayView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTextColor(Color.rgb(0, 255, 180));
        hudView.setBackgroundColor(Color.argb(150, 0, 0, 0));
        hudView.setTextSize(12);
        hudView.setPadding(12, 6, 12, 6);
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
        params.x = 8;
        params.y = 8;

        windowManager.addView(hudView, params);
    }

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
                .setContentTitle("HUD running")
                .setContentText("FPS / CPU / GPU overlay active")
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