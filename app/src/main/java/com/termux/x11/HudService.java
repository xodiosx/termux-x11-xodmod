package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HudService extends Service {

    private static final String CHANNEL_ID = "hud_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private TextView hudView;
    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        createOverlayView();
        startStatsUpdates();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "HUD Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Termux:X11 HUD")
                .setContentText("System monitor overlay is active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createOverlayView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTextColor(Color.WHITE);
        hudView.setBackgroundColor(Color.argb(150, 0, 0, 0));
        hudView.setTextSize(12);
        hudView.setPadding(10, 5, 10, 5);
        hudView.setTypeface(android.graphics.Typeface.MONOSPACE);

        WindowManager.LayoutParams params;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
        } else {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
        }

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 10;
        params.y = 10;

        windowManager.addView(hudView, params);
    }

    private void startStatsUpdates() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            String stats = getCpuUsage() + " | " + getMemoryInfo();
            mainHandler.post(() -> hudView.setText(stats));
        }, 0, 2, TimeUnit.SECONDS);
    }

    private String getCpuUsage() {
        try {
            Process process = Runtime.getRuntime().exec("top -bn 1");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("CPU:")) {
                    return line.trim();
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return "CPU: N/A";
    }

    private String getMemoryInfo() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            int total = 0, free = 0, available = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = Integer.parseInt(line.split("\\s+")[1]);
                } else if (line.startsWith("MemFree:")) {
                    free = Integer.parseInt(line.split("\\s+")[1]);
                } else if (line.startsWith("MemAvailable:")) {
                    available = Integer.parseInt(line.split("\\s+")[1]);
                }
            }
            reader.close();
            if (available > 0) {
                return String.format("MEM: %d MB / %d MB", (total - available) / 1024, total / 1024);
            } else {
                return String.format("MEM: %d MB / %d MB", (total - free) / 1024, total / 1024);
            }
        } catch (Exception e) {
            return "MEM: N/A";
        }
    }

    @Override
    public void onDestroy() {
        if (hudView != null) {
            windowManager.removeView(hudView);
            hudView = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}