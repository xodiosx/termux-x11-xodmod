package com.termux.x11;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HudService extends Service {

    private TextView hudView;
    private String fpsValue = "FPS: N/A";
    private String cpuUsage = "CPU: N/A";
    private String memUsage = "MEM: N/A";
    private boolean running = true;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // hudView should be initialized to your overlay TextView
        // e.g., from WindowManager overlay (not shown here)
        startHudUpdater();
        startFpsLogcatReader();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private void startHudUpdater() {
        new Thread(() -> {
            while (running) {
                updateCpuMemory();
                runOnUiThread(this::updateHud);

                try {
                    Thread.sleep(1000); // update every second
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "hud-updater").start();
    }

    private void updateCpuMemory() {
        cpuUsage = "CPU: " + readCpuUsage() + "%";
        memUsage = "MEM: " + readMemUsage() + "MB";
    }

    private void updateHud() {
        if (hudView == null) return;

        String combined = fpsValue + "\n" + cpuUsage + "\n" + memUsage;
        SpannableString text = new SpannableString(combined);

        int start = 0;

        // FPS color
        int fpsEnd = fpsValue.length();
        int fpsColor = Color.GREEN;
        try {
            float fps = Float.parseFloat(fpsValue.replaceAll("[^0-9.]", ""));
            if (fps < 30) fpsColor = Color.RED;
            else if (fps < 50) fpsColor = Color.YELLOW;
        } catch (Exception ignored) {}
        text.setSpan(new ForegroundColorSpan(fpsColor),
                start, fpsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // CPU color
        start = fpsEnd + 1;
        int cpuEnd = start + cpuUsage.length();
        text.setSpan(new ForegroundColorSpan(Color.rgb(255, 165, 0)), // orange
                start, cpuEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // MEM color
        start = cpuEnd + 1;
        text.setSpan(new ForegroundColorSpan(Color.CYAN),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        hudView.setText(text);
    }

    private void runOnUiThread(Runnable r) {
        hudView.post(r);
    }

    // --- CPU usage ---
    private float readCpuUsage() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/stat"));
            String line = br.readLine();
            br.close();
            String[] toks = line.split("\\s+");
            long user = Long.parseLong(toks[1]);
            long nice = Long.parseLong(toks[2]);
            long system = Long.parseLong(toks[3]);
            long idle = Long.parseLong(toks[4]);
            long total = user + nice + system + idle;

            // simple usage %
            return (float)(total - idle) * 100 / total;
        } catch (Exception e) {
            return 0f;
        }
    }

    // --- Memory usage ---
    private long readMemUsage() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line = br.readLine(); // MemTotal
            long total = Long.parseLong(line.replaceAll("[^0-9]", ""));
            line = br.readLine(); // MemFree
            long free = Long.parseLong(line.replaceAll("[^0-9]", ""));
            br.close();
            return (total - free) / 1024; // MB
        } catch (Exception e) {
            return 0;
        }
    }

    // --- FPS reader ---
    private void startFpsLogcatReader() {
        new Thread(() -> {
            try {
                java.lang.Process p =
                        Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief"});

                BufferedReader br =
                        new BufferedReader(new InputStreamReader(p.getInputStream()));

                Pattern fpsPattern = Pattern.compile("=\\s*([0-9]+(\\.[0-9]+)?)\\s*FPS");

                String line;
                while ((line = br.readLine()) != null && running) {
                    if (line.contains("LorieNative") && line.contains("FPS")) {
                        Matcher m = fpsPattern.matcher(line);
                        if (m.find()) {
                            fpsValue = "FPS: " + m.group(1);
                        }
                    }
                }
            } catch (Exception e) {
                fpsValue = "FPS: N/A";
            }
        }, "fps-logcat").start();
    }
}