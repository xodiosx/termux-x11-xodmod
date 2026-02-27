package com.termux.x11;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.util.DisplayMetrics;
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
    private static final String GPU_INFO_FILE = "gpuinfo"; // in getFilesDir()

    /* ---------- ACTIVITY ATTACHMENT ---------- */
    private WeakReference<Activity> activityRef;
    private TextView hudView;
    private boolean attached = false;

    /* ---------- THREADING ---------- */
    private ScheduledExecutorService hudScheduler;
    private ScheduledExecutorService gpuScheduler;
    private Handler mainHandler;

    /* ---------- FPS ---------- */
    private volatile String fpsText = "FPS: N/A";
    private volatile float fpsValue = -1f;
    private Thread fpsThread;
    private volatile boolean fpsRunning = true;

    /* ---------- CPU ---------- */
    private long lastAppCpuTicks = 0;
    private long lastWallTimeMs = 0;

    /* ---------- GPU (simplified) ---------- */
    private volatile String openGLRenderer = "OGL: ...";
    private volatile String vulkanDeviceName = "VK: ...";

    /* ---------- MEM / TEMP ---------- */
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
        totalRam = getTotalRam();
        startFpsReader();
        startGpuInfoFetcher();
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
        hudScheduler = Executors.newSingleThreadScheduledExecutor();
        hudScheduler.scheduleAtFixedRate(() -> {
            String fullText = buildHudText();
            SpannableString colored = colorizeText(fullText);

            mainHandler.post(() -> {
                if (attached && hudView != null) {
                    hudView.setText(colored);
                    adjustTextSizeToFit(hudView, colored.toString());
                }
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== GPU INFO FETCHER ===================== */

    private void startGpuInfoFetcher() {
        gpuScheduler = Executors.newSingleThreadScheduledExecutor();
        gpuScheduler.scheduleAtFixedRate(this::fetchGpuInfo, 0, 5, TimeUnit.SECONDS);
    }

    private void fetchGpuInfo() {
        // Try file first (written by the container)
        if (readGpuInfoFromFile()) {
            return;
        }

        // Then try environment variables (set in Termux X11 launch environment)
        if (readGpuInfoFromEnv()) {
            return;
        }

        // Then try running commands inside the container
        if (readGpuInfoFromContainer()) {
            return;
        }

        // Finally fall back to Android system properties
        fallbackGpuInfo();
    }

    /**
     * Reads GPU info from a file in the app's private storage.
     * Expected format:
     *   OGL=renderer string
     *   VK=device name
     */
    private boolean readGpuInfoFromFile() {
        File file = new File(getFilesDir(), GPU_INFO_FILE);
        if (!file.exists()) return false;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            String ogl = null;
            String vk = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("OGL=")) {
                    ogl = line.substring(4).trim();
                } else if (line.startsWith("VK=")) {
                    vk = line.substring(3).trim();
                }
            }
            if (ogl != null && !ogl.isEmpty()) {
                openGLRenderer = "OGL: " + simplifyGpuString(ogl);
            }
            if (vk != null && !vk.isEmpty()) {
                vulkanDeviceName = "VK: " + simplifyGpuString(vk);
            }
            return (ogl != null && !ogl.isEmpty()) || (vk != null && !vk.isEmpty());
        } catch (Exception e) {
            Log.d(TAG, "Failed to read GPU info file", e);
            return false;
        }
    }

    /**
     * Reads GPU info from environment variables OGLGPU and VKGPU.
     */
    private boolean readGpuInfoFromEnv() {
        String envOgl = System.getenv("OGLGPU");
        String envVk = System.getenv("VKGPU");
        boolean updated = false;

        if (envOgl != null && !envOgl.trim().isEmpty()) {
            openGLRenderer = "OGL: " + simplifyGpuString(envOgl.trim());
            updated = true;
        }
        if (envVk != null && !envVk.trim().isEmpty()) {
            vulkanDeviceName = "VK: " + simplifyGpuString(envVk.trim());
            updated = true;
        }
        return updated;
    }

    /**
     * Runs glxinfo and vulkaninfo inside the container (inheriting DISPLAY/PATH).
     */
    private boolean readGpuInfoFromContainer() {
        try {
            String[] cmd = {
                    "/sysystem/bin/sh", "-c",
                    "export DISPLAY=$DISPLAY; export PATH=$PATH; " +
                    "OGLGPU=$OGLGPU" +
                    "VKGPU=$VKGPU" +
                    "echo \"OGL:$OGLGPU\"; echo \"VK:$VKGPU\""
            };

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String ogl = null;
            String vk = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("OGL:")) {
                    ogl = line.substring(4).trim();
                    if (ogl.isEmpty()) ogl = null;
                } else if (line.startsWith("VK:")) {
                    vk = line.substring(3).trim();
                    if (vk.isEmpty()) vk = null;
                }
            }
            int exitCode = process.waitFor();

            if (exitCode == 0 && (ogl != null || vk != null)) {
                if (ogl != null) openGLRenderer = "OGL: " + simplifyGpuString(ogl);
                if (vk != null) vulkanDeviceName = "VK: " + simplifyGpuString(vk);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to run container GPU commands", e);
        }
        return false;
    }

    private void fallbackGpuInfo() {
        String ogl = getProp("ro.hardware.egl");
        String vk = getProp("ro.hardware.vulkan");
        if (ogl == null || ogl.isEmpty()) ogl = getProp("ro.board.platform");
        if (vk == null || vk.isEmpty()) vk = ogl;

        openGLRenderer = "OGL: " + simplifyGpuString(ogl != null ? ogl : "Unknown");
        vulkanDeviceName = "VK: " + simplifyGpuString(vk != null ? vk : "Unknown");
    }

    /**
     * Simplifies a GPU name to a short identifier.
     * - Looks for known keywords (case‑insensitive) and returns the first match.
     * - If no keyword matches, returns the first word.
     */
    private String simplifyGpuString(String raw) {
        if (raw == null || raw.isEmpty()) return "?";

        String lower = raw.toLowerCase();
        String[] keywords = {
            "gl4es", "venus", "Mali", "adreno", "mali", "powervr",
            "turnip", "llvm", "swrast", "virgl", "VIRGL",
            "panfrost", "v3d", "vc4", "nvidia", "geforce", "radeon",
            "amd", "intel", "iris", "crocus", "zink", "lavapipe"
        };

        for (String kw : keywords) {
            if (lower.contains(kw)) {
                // Return the original case of the first occurrence of the keyword
                int idx = lower.indexOf(kw);
                return raw.substring(idx, idx + kw.length());
            }
        }

        // No keyword match → return first word
        String[] parts = raw.trim().split("\\s+");
        return parts[0];
    }

    /* ===================== BUILD HUD TEXT ===================== */

    private String buildHudText() {
        String fps = fpsText;
        String temp = getCpuTemp();
        String cpu = getCpuUsage();
        String mem = getMemoryInfo();

        return fps + " | " + temp + " | " + cpu + " | " +
               openGLRenderer + " | " + vulkanDeviceName + " | " + mem;
    }

    private SpannableString colorizeText(String full) {
        SpannableString s = new SpannableString(full);

        color(s, fpsText, fpsValue >= 0 && fpsValue < 10 ? Color.RED : Color.GREEN);

        float tempVal = getCpuTempValue();
        color(s, getCpuTemp(), tempColor(tempVal));

        color(s, getCpuUsage(), Color.YELLOW);
        color(s, openGLRenderer, Color.MAGENTA);
        color(s, vulkanDeviceName, Color.MAGENTA);

        long availMB = getAvailableMemoryMB();
        color(s, getMemoryInfo(), (availMB >= 0 && availMB < 800) ? Color.RED : Color.CYAN);

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

    private int tempColor(float tempVal) {
        if (tempVal < 0) return Color.LTGRAY;
        else if (tempVal > 70) return Color.RED;
        else if (tempVal > 40) return Color.rgb(255, 165, 0);
        else return Color.CYAN;
    }

    private void adjustTextSizeToFit(TextView textView, String text) {
        if (textView == null || text == null) return;

        Activity act = activityRef != null ? activityRef.get() : null;
        if (act == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int availableWidth = screenWidth - textView.getPaddingLeft() - textView.getPaddingRight() - 16;

        Paint paint = new Paint();
        paint.setTypeface(textView.getTypeface());
        paint.setTextSize(textView.getTextSize());

        float textWidth = paint.measureText(text);
        if (textWidth <= availableWidth) return;

        float minSizePx = 8 * metrics.density;
        float newSizePx = textView.getTextSize();
        while (newSizePx > minSizePx && textWidth > availableWidth) {
            newSizePx -= 1;
            paint.setTextSize(newSizePx);
            textWidth = paint.measureText(text);
        }
        textView.setTextSize(newSizePx / metrics.density);
    }

    /* ===================== FPS READER ===================== */

    private void startFpsReader() {
        fpsThread = new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
                ProcessBuilder pb = new ProcessBuilder(
                        "logcat", "-s", "LorieNative:I", "-v", "brief"
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

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

    /* ===================== CPU USAGE ===================== */

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

        long dCpu = totalCpuTicks - lastAppCpuTicks;
        long dTime = now - lastWallTimeMs;

        lastWallTimeMs = now;
        lastAppCpuTicks = totalCpuTicks;

        if (dTime <= 0) return "CPU: 0%";

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
            return mi.availMem / (1024 * 1024);
        } catch (Exception e) {
            return -1;
        }
    }

    private String formatBytes(long b) {
        int e = (int) (Math.log(b) / Math.log(1024));
        return String.format(Locale.US, "%.1f %sB",
                b / Math.pow(1024, e), "KMGTPE".charAt(e - 1));
    }

    /* ===================== SYSTEM PROPERTY HELPER ===================== */

    private String getProp(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
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
        if (hudScheduler != null) hudScheduler.shutdownNow();
        if (gpuScheduler != null) gpuScheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}