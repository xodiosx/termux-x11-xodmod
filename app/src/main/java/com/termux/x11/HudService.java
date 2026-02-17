package com.termux.x11;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HudService extends Service {

    private static final String TAG = "HudService";

    /* ---------- ACTIVITY ATTACHMENT ---------- */
    private WeakReference<Activity> activityRef;
    private TextView hudView;
    private boolean attached = false;

    /* ---------- THREADING ---------- */
    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    /* ---------- FPS ---------- */
    private volatile String fpsText = "FPS: N/A";
    private volatile float fpsValue = -1f;
    private Thread fpsThread;
    private volatile boolean fpsRunning = true;

    /* ---------- CPU (whole app) ---------- */
    private long lastAppCpuTicks = 0;
    private long lastWallTimeMs = 0;

    /* ---------- GPU / MEM / TEMP ---------- */
    private String gpuName = "GPU: Unknown";
    private String totalRam;

    /* ===================== SERVICE ===================== */

    public class LocalBinder extends Binder {
        public HudService getService() {
            return HudService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        gpuName = detectGpuName();
        totalRam = getTotalRam();
        startFpsReader();
        startHudLoop();
    }

    /* ===================== ACTIVITY BINDING ===================== */

    public void attachToActivity(Activity activity) {
        detach();
        activityRef = new WeakReference<>(activity);

        mainHandler.post(() -> {
            Activity act = activityRef.get();
            if (act == null) return;

            hudView = new TextView(act);
            hudView.setTypeface(Typeface.MONOSPACE);
            hudView.setTextSize(12);
            hudView.setPadding(12, 6, 12, 6);
            hudView.setBackgroundColor(Color.argb(160, 0, 0, 0));

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP | Gravity.START
                    );
            params.leftMargin = 8;
            params.topMargin = 0;

            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            decor.addView(hudView, params);
            attached = true;
            Log.d(TAG, "HUD attached to activity");
        });
    }

    public void detach() {
        mainHandler.post(() -> {
            if (!attached || hudView == null) return;
            Activity act = activityRef != null ? activityRef.get() : null;
            if (act == null) return;

            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            decor.removeView(hudView);
            hudView = null;
            attached = false;
            Log.d(TAG, "HUD detached");
        });
    }

    /* ===================== HUD UPDATE LOOP ===================== */

    private void startHudLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            SpannableString text = buildHudText();
            mainHandler.post(() -> {
                if (attached && hudView != null) {
                    hudView.setText(text);
                }
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== BUILD HUD WITH COLORS ===================== */

    private SpannableString buildHudText() {
        String fps = fpsText;
        String temp = getCpuTemp();          // e.g. "CPU: 45.2°C"
        String cpu = getCpuUsage();
        String mem = getMemoryInfo();
        long availMB = getAvailableMemoryMB();
        float tempVal = getCpuTempValue();   // numeric for coloring

        String full = fps + " | " + temp + " | " + cpu + " | " + gpuName + " | " + mem;
        SpannableString s = new SpannableString(full);

        // FPS color
        color(s, fps, fpsValue >= 0 && fpsValue < 10 ? Color.RED : Color.GREEN);

        // Temperature color
        int tempColor;
        if (tempVal < 0) {
            tempColor = Color.LTGRAY;      // unavailable
        } else if (tempVal > 70) {
            tempColor = Color.RED;
        } else if (tempVal > 40) {
            tempColor = Color.rgb(255, 165, 0); // orange
        } else {
            tempColor = Color.CYAN;
        }
        color(s, temp, tempColor);

        // CPU usage always yellow
        color(s, cpu, Color.YELLOW);

        // GPU name magenta
        color(s, gpuName, Color.MAGENTA);

        // Memory red if < 800 MB available
        int memColor = (availMB >= 0 && availMB < 800) ? Color.RED : Color.CYAN;
        color(s, mem, memColor);

        return s;
    }

    private void color(SpannableString s, String part, int color) {
        int start = s.toString().indexOf(part);
        if (start >= 0) {
            s.setSpan(new ForegroundColorSpan(color),
                    start, start + part.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /* ===================== FPS READER (with logcat -c) ===================== */

    private void startFpsReader() {
    fpsThread = new Thread(() -> {
        try {
            File logcatBin = ensureLogcatBinary();

            ProcessBuilder pb = new ProcessBuilder(
                    logcatBin.getAbsolutePath(),
                    "-v", "brief",
                    "-s", "LorieNative:I"
            );

            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.getInputStream()));

            String line;
            while (fpsRunning && (line = br.readLine()) != null) {
                if (!line.contains("FPS")) continue;
                parseFps(line);
            }

        } catch (Exception e) {
            Log.e(TAG, "FPS reader error", e);
        }
    }, "FPS-Reader");

    fpsThread.setDaemon(true);
    fpsThread.start();
}

    private void parseFps(String line) {
        int idx = line.lastIndexOf('=');
        if (idx < 0) return;

        String num = line.substring(idx + 1).replace("FPS", "").trim();
        try {
            fpsValue = Float.parseFloat(num);
            fpsText = "FPS: " + num;
        } catch (Exception ignored) {}
    }

    /* ===================== CPU USAGE (whole app UID) ===================== */

    private int[] getAppPids() {
        int uid = android.os.Process.myUid();
        ArrayList<Integer> pids = new ArrayList<>();

        File proc = new File("/proc");
        File[] files = proc.listFiles();
        if (files == null) return new int[0];

        for (File f : files) {
            if (!f.isDirectory()) continue;

            int pid;
            try {
                pid = Integer.parseInt(f.getName());
            } catch (NumberFormatException e) {
                continue;
            }

            try (BufferedReader br =
                         new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.split("\\s+");
                        int procUid = Integer.parseInt(parts[1]);
                        if (procUid == uid) {
                            pids.add(pid);
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        int[] out = new int[pids.size()];
        for (int i = 0; i < pids.size(); i++) out[i] = pids.get(i);
        return out;
    }

    private long readProcessCpuTicks(int pid) {
        try (BufferedReader br =
                     new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {

            String[] t = br.readLine().split("\\s+");
            long utime = Long.parseLong(t[13]);
            long stime = Long.parseLong(t[14]);
            return utime + stime;

        } catch (Exception e) {
            return 0;
        }
    }

    private String getCpuUsage() {
        long now = SystemClock.elapsedRealtime();
        int[] pids = getAppPids();

        long totalCpuTicks = 0;
        for (int pid : pids) {
            totalCpuTicks += readProcessCpuTicks(pid);
        }

        if (lastWallTimeMs == 0) {
            lastWallTimeMs = now;
            lastAppCpuTicks = totalCpuTicks;
            return "CPU: ...";
        }

        long dCpu = totalCpuTicks - lastAppCpuTicks;   // ticks
        long dTime = now - lastWallTimeMs;              // ms

        lastWallTimeMs = now;
        lastAppCpuTicks = totalCpuTicks;

        if (dTime <= 0) return "CPU: 0%";

        // USER_HZ = 100 → 1 tick = 10 ms
        // cpu_ms = dCpu * 10
        // usage% = (cpu_ms / dTime) * 100 = dCpu * 1000 / dTime
        float usage = (dCpu * 1000f) / dTime;

        return String.format(Locale.US, "CPU: %.1f%%", usage);
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

    private float getCpuTempValue() {
        for (int i = 0; i < 10; i++) {
            try {
                String path = "/sys/class/thermal/thermal_zone" + i + "/temp";
                BufferedReader br = new BufferedReader(new FileReader(path));
                int temp = Integer.parseInt(br.readLine().trim());
                br.close();

                if (temp > 10000) {
                    return temp / 1000f;
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /* ===================== MEMORY ===================== */

    private String getMemoryInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        return "MEM: " + formatBytes(used) + " / " + totalRam;
    }

    private String getTotalRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return formatBytes(mi.totalMem);
    }

    private long getAvailableMemoryMB() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem / (1024 * 1024); // MB
        } catch (Exception e) {
            return -1;
        }
    }

    private String formatBytes(long b) {
        int e = (int) (Math.log(b) / Math.log(1024));
        return String.format(Locale.US, "%.1f %sB",
                b / Math.pow(1024, e), "KMGTPE".charAt(e - 1));
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
            if (v != null && !v.isEmpty())
                return "GPU: " + v;
        }
        return "GPU: Unknown";
    }

    private String getProp(String key) {
        try {
            java.lang.Process p =
                    Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /* ===================== CLEANUP ===================== */

    @Override
    public void onDestroy() {
        fpsRunning = false;
        detach();
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    /// logcat 
    private File ensureLogcatBinary() throws Exception {
    File binDir = new File(getFilesDir(), "bin");
    if (!binDir.exists()) binDir.mkdirs();

    File logcat = new File(binDir, "logcat");

    if (!logcat.exists()) {
        try (java.io.InputStream in = getAssets().open("logcat");
             java.io.FileOutputStream out = new java.io.FileOutputStream(logcat)) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }

        Runtime.getRuntime()
                .exec(new String[]{"chmod", "755", logcat.getAbsolutePath()})
                .waitFor();
    }

    return logcat;
}
    
    
    
    
}