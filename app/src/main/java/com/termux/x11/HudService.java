package com.termux.x11;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.FileReader;
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

    /* ================= FPS ================= */

    private int frameCount = 0;
    private int fps = 0;
    private long lastFpsTime = 0;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    frameCount++;
                    long now = System.currentTimeMillis();
                    if (now - lastFpsTime >= 1000) {
                        fps = frameCount;
                        frameCount = 0;
                        lastFpsTime = now;
                    }
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };

    /* ================= CPU ================= */

    private long lastIdle = 0;
    private long lastTotal = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        createOverlay();
        startFpsTracking();
        startStatsLoop();
    }

    /* ================= OVERLAY ================= */

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        hudView = new TextView(this);
        hudView.setTextColor(Color.WHITE);
        hudView.setBackgroundColor(Color.argb(150, 0, 0, 0));
        hudView.setTextSize(12);
        hudView.setPadding(10, 5, 10, 5);
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

    /* ================= FPS ================= */

    private void startFpsTracking() {
        lastFpsTime = System.currentTimeMillis();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /* ================= MAIN LOOP ================= */

    private void startStatsLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            String text =
                    "FPS: " + fps + " | " +
                    getCpuUsage();

            mainHandler.post(() -> hudView.setText(text));
        }, 0, 1, TimeUnit.SECONDS);
    }

    /* ================= CPU USAGE ================= */

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

            if (deltaTotal <= 0) return "CPU: N/A";

            float usage = (deltaTotal - deltaIdle) * 100f / deltaTotal;
            return String.format("CPU: %.1f%%", usage);

        } catch (Exception e) {
            return "CPU: N/A";
        }
    }

    /* ================= NOTIFICATION ================= */

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
                .setContentText("FPS & CPU monitor running")
                .setSmallIcon(android.R.drawable.presence_online)
                .build();
    }

    @Override
    public void onDestroy() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (hudView != null) windowManager.removeView(hudView);
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return null;
    }
}