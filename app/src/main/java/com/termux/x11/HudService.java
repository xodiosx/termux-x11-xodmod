package com.termux.x11;

import java.util.Locale;
import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.*;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.io.*;
import java.util.concurrent.*;

public class HudService extends Service {

    private static final String CHANNEL_ID = "hud_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "HudService";

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

        startFpsReaderThread();
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
        String fps = fpsValue;
        String temp = getCpuTemp();
        String cpu = getCpuUsage();
        String mem = getMemoryInfo();
        String gpu = gpuName;

        String full = fps + " | " + temp + " | " + cpu + " | " + gpu + " | " + mem;
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
            s.setSpan(new ForegroundColorSpan(color), start,
                    start + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /* ===================== BINARY SEARCH (reusable) ===================== */

    private String findBinary(String binaryName) {
        // Look inside app's private data first (e.g., files/usr/bin/)
        File customBin = new File(getFilesDir(), "usr/bin/" + binaryName);
        if (customBin.exists() && customBin.canExecute()) {
            Log.d(TAG, "Found " + binaryName + " in app data: " + customBin.getAbsolutePath());
            return customBin.getAbsolutePath();
        }

        // Common system binary paths
        String[] systemPaths = {
            "/system/bin/" + binaryName,
            "/system/xbin/" + binaryName,
            "/bin/" + binaryName,
            "/vendor/bin/" + binaryName
        };
        for (String path : systemPaths) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                Log.d(TAG, "Found system " + binaryName + ": " + path);
                return path;
            }
        }

        Log.d(TAG, "Binary " + binaryName + " not found");
        return null;
    }

    /* ===================== FPS READER (no file logging) ===================== */

    private void startFpsReaderThread() {
        fpsReaderRunning = true;
        fpsReaderThread = new Thread(() -> {
            java.lang.Process process = null;
            BufferedReader reader = null;
            String grepPath = findBinary("grep");

            try {
                // Clear logcat buffer (optional)
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();

                ProcessBuilder pb;
                String commandDescription;

                if (grepPath != null) {
                    // Use grep with --line-buffered
                    String logcatCmd = "logcat -s LorieNative:I -v time";
                    String grepCmd = grepPath + " --line-buffered \"FPS\"";
                    String fullCmd = logcatCmd + " | " + grepCmd;
                    pb = new ProcessBuilder("sh", "-c", fullCmd);
                    commandDescription = "grep pipeline: " + fullCmd;
                } else {
                    // No grep: read all logcat lines with tag filter, filter in Java
                    pb = new ProcessBuilder("logcat", "-s", "LorieNative:I", "-v", "time");
                    commandDescription = "logcat with tag filter (Java parsing)";
                }

                pb.redirectErrorStream(true);
                process = pb.start();

                Log.d(TAG, "FPS reader thread started, command: " + commandDescription);

                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while (fpsReaderRunning && (line = reader.readLine()) != null) {
                    // Only parse FPS lines, no file logging
                    if (line.contains("FPS")) {
                        parseFpsLine(line);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FPS reader thread", e);
                fpsValue = "FPS: error";
            } finally {
                if (reader != null) try { reader.close(); } catch (IOException ignored) {}
                if (process != null) process.destroy();
            }
        }, "FPS-Logcat-Reader");
        fpsReaderThread.setDaemon(true);
        fpsReaderThread.start();
    }

    private void parseFpsLine(String line) {
        // Expect format: "... = 6.8 FPS"
        int idx = line.lastIndexOf('=');
        if (idx != -1) {
            String afterEq = line.substring(idx + 1).trim();
            String[] parts = afterEq.split("\\s+");
            if (parts.length > 0) {
                fpsValue = "FPS: " + parts[0];
                Log.d(TAG, "Updated FPS: " + fpsValue);
            }
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

    /* ===================== CPU USAGE (binary-aware) ===================== */

    private String getCpuUsage() {
        try {
            // Find required binaries
            String shPath = findBinary("sh");
            String awkPath = findBinary("awk");
            if (shPath == null || awkPath == null) {
                Log.w(TAG, "sh or awk not found, CPU usage N/A");
                return "CPU: N/A";
            }

            int pid = android.os.Process.myPid();
            int hz = 100; // Android default CLK_TCK

            // Build command using found paths
            // We'll use a here-document style to avoid process substitution issues
            String cmd = shPath + " -c '" +
                "PID=" + pid + " && " +
                "HZ=" + hz + " && " +
                "read utime1 stime1 < <(" + awkPath + " \"{print $14, $15}\" /proc/$PID/stat) && " +
                "sleep 1 && " +
                "read utime2 stime2 < <(" + awkPath + " \"{print $14, $15}\" /proc/$PID/stat) && " +
                "delta=$(( (utime2 + stime2) - (utime1 + stime1) )) && " +
                "cpu=$(" + awkPath + " -v d=$delta -v h=$HZ 'BEGIN { printf \"%.1f\", (d/h)*100 }') && " +
                "echo $cpu" +
                "'";

            java.lang.Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            process.waitFor();

            if (line != null && !line.isEmpty()) {
                return "CPU: " + line + "%";
            } else {
                return "CPU: N/A";
            }
        } catch (Exception e) {
            Log.e(TAG, "CPU usage error", e);
            return "CPU: N/A";
        }
    }

    /* ===================== GPU NAME ===================== */

    private String detectGpuName() {
        String[] props = { "ro.hardware.vulkan", "ro.hardware.egl", "ro.board.platform" };
        for (String p : props) {
            String v = getProp(p);
            if (v != null && !v.isEmpty()) return "GPU: " + v;
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
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HUD Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
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
        fpsReaderRunning = false;
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