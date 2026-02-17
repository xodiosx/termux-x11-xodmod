package com.termux.x11;

import java.util.Locale;
import android.app.*;
import android.content.*;
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

    // Binder for activity communication
    private final IBinder binder = new LocalBinder();

    // Activity reference (weak to avoid leaks)
    private WeakReference<Activity> targetActivity = null;
    private TextView hudView = null;
    private boolean isAttached = false;

    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    /* ---------- FPS STATE ---------- */
    private volatile String fpsValue = "FPS: N/A";
    private volatile float fpsNumeric = -1f; // for threshold coloring
    private Thread fpsReaderThread;
    private volatile boolean fpsReaderRunning = true;

    /* ---------- CPU STATE ---------- */
    // (no changes needed)

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

        // Start background threads (they will update the UI only when attached)
        startFpsReaderThread();
        startStatsLoop();
    }

    /**
     * Called by the main activity to attach the HUD to its window.
     */
    public void attachToActivity(Activity activity) {
        if (targetActivity != null && targetActivity.get() == activity && isAttached) {
            return; // already attached to this activity
        }

        // Remove any previously attached HUD
        removeHudView();

        targetActivity = new WeakReference<>(activity);

        // Create and attach the HUD view on UI thread
        mainHandler.post(() -> {
            if (targetActivity == null || targetActivity.get() == null) return;
            Activity act = targetActivity.get();

            // Create the HUD TextView
            hudView = new TextView(act);
            hudView.setTextSize(12);
            hudView.setPadding(10, 4, 10, 4);
            hudView.setTypeface(Typeface.MONOSPACE);
            hudView.setBackgroundColor(Color.argb(140, 0, 0, 0));

            // Use activity's WindowManager with its token
            WindowManager wm = act.getWindowManager();
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, // attaches to activity
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
            );

            // Critical: use the activity's window token
            params.token = act.getWindow().getDecorView().getWindowToken();
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 5;
            params.y = 0;

            wm.addView(hudView, params);
            isAttached = true;

            Log.d(TAG, "HUD attached to activity window");
        });
    }

    /**
     * Remove the HUD view from its current window.
     */
    private void removeHudView() {
        if (hudView != null && isAttached && targetActivity != null) {
            Activity act = targetActivity.get();
            if (act != null && !act.isFinishing()) {
                try {
                    WindowManager wm = act.getWindowManager();
                    wm.removeView(hudView);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing HUD view", e);
                }
            }
            hudView = null;
            isAttached = false;
        }
    }

    /* ===================== MAIN LOOP ===================== */

    private void startStatsLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            // Build HUD text with dynamic colors
            SpannableString hudText = buildColoredHud();

            // Update the TextView if attached
            mainHandler.post(() -> {
                if (hudView != null && isAttached) {
                    hudView.setText(hudText);
                }
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== HUD TEXT with Dynamic Colors ===================== */

    private SpannableString buildColoredHud() {
        String fps = fpsValue;
        String temp = getCpuTemp();
        String cpu = getCpuUsage();
        String mem = getMemoryInfo();
        String gpu = gpuName;

        String full = fps + " | " + temp + " | " + cpu + " | " + gpu + " | " + mem;
        SpannableString s = new SpannableString(full);

        // Color each part; thresholds override default colors
        colorPart(s, fps, getFpsColor());
        colorPart(s, temp, Color.CYAN);
        colorPart(s, cpu, Color.YELLOW);
        colorPart(s, gpu, Color.MAGENTA);
        colorPart(s, mem, getMemoryColor());

        return s;
    }

    private int getFpsColor() {
        // fpsNumeric is updated in parseFpsLine()
        if (fpsNumeric >= 0 && fpsNumeric < 10) {
            return Color.RED;
        }
        return Color.GREEN; // default
    }

    private int getMemoryColor() {
        long availableMB = getAvailableMemoryMB();
        if (availableMB >= 0 && availableMB < 800) {
            return Color.RED;
        }
        return Color.LTGRAY; // default
    }

    private void colorPart(SpannableString s, String part, int color) {
        int start = s.toString().indexOf(part);
        if (start >= 0) {
            s.setSpan(new ForegroundColorSpan(color), start,
                    start + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /* ===================== BINARY SEARCH ===================== */

    private String findBinary(String binaryName) {
        File customBin = new File(getFilesDir(), "usr/bin/" + binaryName);
        if (customBin.exists() && customBin.canExecute()) {
            Log.d(TAG, "Found " + binaryName + " in app data: " + customBin.getAbsolutePath());
            return customBin.getAbsolutePath();
        }
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

    /* ===================== FPS READER ===================== */

    private void startFpsReaderThread() {
        fpsReaderRunning = true;
        fpsReaderThread = new Thread(() -> {
            java.lang.Process process = null;  // fully qualified
            BufferedReader reader = null;
            String grepPath = findBinary("grep");

            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();

                ProcessBuilder pb;
                if (grepPath != null) {
                    String logcatCmd = "logcat -s LorieNative:I -v time";
                    String grepCmd = grepPath + " --line-buffered \"FPS\"";
                    String fullCmd = logcatCmd + " | " + grepCmd;
                    pb = new ProcessBuilder("sh", "-c", fullCmd);
                } else {
                    pb = new ProcessBuilder("logcat", "-s", "LorieNative:I", "-v", "time");
                }

                pb.redirectErrorStream(true);
                process = pb.start();
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while (fpsReaderRunning && (line = reader.readLine()) != null) {
                    if (line.contains("FPS")) {
                        parseFpsLine(line);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FPS reader thread", e);
                fpsValue = "FPS: error";
                fpsNumeric = -1;
            } finally {
                if (reader != null) try { reader.close(); } catch (IOException ignored) {}
                if (process != null) process.destroy();
            }
        }, "FPS-Logcat-Reader");
        fpsReaderThread.setDaemon(true);
        fpsReaderThread.start();
    }

    private void parseFpsLine(String line) {
        int idx = line.lastIndexOf('=');
        if (idx != -1) {
            String afterEq = line.substring(idx + 1).trim();
            String[] parts = afterEq.split("\\s+");
            if (parts.length > 0) {
                String numStr = parts[0];
                fpsValue = "FPS: " + numStr;
                try {
                    fpsNumeric = Float.parseFloat(numStr);
                } catch (NumberFormatException e) {
                    fpsNumeric = -1;
                }
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

    /* ===================== CPU USAGE ===================== */

    private String getCpuUsage() {
        try {
            String shPath = findBinary("sh");
            String awkPath = findBinary("awk");
            if (shPath == null || awkPath == null) {
                return "CPU: N/A";
            }

            int pid = android.os.Process.myPid();
            int hz = 100;

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

            java.lang.Process process = Runtime.getRuntime().exec(cmd); // fully qualified
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
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"getprop", key}); // fully qualified
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

    /** Returns available memory in MB, or -1 if unable. */
    private long getAvailableMemoryMB() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            long availableBytes = memoryInfo.availMem;
            return availableBytes / (1024 * 1024); // MB
        } catch (Exception e) {
            return -1;
        }
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

    /* ===================== BINDING ===================== */

    public class LocalBinder extends Binder {
        public HudService getService() {
            return HudService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        fpsReaderRunning = false;
        if (fpsReaderThread != null) fpsReaderThread.interrupt();
        removeHudView();
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }
}