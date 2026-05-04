package com.termux.x11.controller.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.MainActivity;
import com.termux.x11.R;
import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.core.CPUStatus;
import com.termux.x11.controller.core.ProcessHelper;
import com.termux.x11.controller.core.StringUtils;
import com.termux.x11.controller.widget.CPUListView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class TaskManagerDialog extends ContentDialog implements OnGetProcessInfoListener {
    private final MainActivity activity;
    private final LayoutInflater inflater;
    private Timer timer;
    private final Object lock = new Object();

    // Dynamically set in initEnvironment()
    private String[] env;
    private String shellPath;

    public TaskManagerDialog(MainActivity activity) {
        super(activity, R.layout.task_manager_dialog);
        this.activity = activity;
        setCancelable(false);
        setTitle(R.string.task_manager);
        setIcon(R.drawable.icon_task_manager);

        // Set up the correct environment before building UI
        initEnvironment();

        Button cancelButton = findViewById(R.id.BTCancel);
        cancelButton.setText(R.string.new_task);
        cancelButton.setOnClickListener((v) -> {
            dismiss();
            ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe", (command) -> {
                if (command == null || command.trim().isEmpty()) return;
                String cmd = command.trim();
                if (cmd.toLowerCase().endsWith(".exe")) {
                    runNativeCommand("runwine " + cmd);
                } else {
                    runNativeCommand(cmd);
                }
                Toast.makeText(activity, "Running: " + cmd, Toast.LENGTH_SHORT).show();
            });
        });

        setOnDismissListener((dialog) -> {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            activity.getTermuxProcessorInfo("1");
            final LinearLayout container = findViewById(R.id.LLProcessList);
            container.removeAllViews();
            activity.getWinHandler().setOnGetProcessInfoListener(null);
        });
        inflater = LayoutInflater.from(activity);
    }

    /**
     * Determines the base directory, DISPLAY, and shell path based on
     * which environment (Termux or Xodos) is accessible.
     */
    private void initEnvironment() {
        String baseDir;
        String display;

        // Check which app's data directory exists
        if (new File("/data/data/com.termux/files").exists()) {
            baseDir = "/data/data/com.termux/files";
        } else {
            baseDir = "/data/data/com.xodos/files";
        }

        // DISPLAY: use system variable if set, otherwise default for each environment
        String systemDisplay = System.getenv("DISPLAY");
        if (systemDisplay != null && !systemDisplay.isEmpty()) {
            display = systemDisplay;
        } else if (baseDir.contains("com.termux")) {
            display = ":0";
        } else {
            display = ":4";
        }

        // Build environment array
        env = new String[] {
            "PREFIX=" + baseDir + "/usr",
            "HOME=" + baseDir + "/home",
            "TMPDIR=" + baseDir + "/usr/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" + baseDir + "/usr/bin",
            "DISPLAY=" + display,
            "XDG_RUNTIME_DIR=" + baseDir + "/usr/tmp"
        };

        // Shell path
        shellPath = baseDir + "/usr/bin/bash";
    }

    private void runNativeCommand(String command) {
        new Thread(() -> {
            try {
                Process proc = Runtime.getRuntime().exec(
                    new String[] { shellPath, "-c", command },
                    env
                );
                proc.getOutputStream().close();
                new Thread(() -> consumeStream(proc.getInputStream())).start();
                new Thread(() -> consumeStream(proc.getErrorStream())).start();
                proc.waitFor();
            } catch (IOException e) {
                activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Failed to run: " + command, Toast.LENGTH_SHORT).show()
                );
            } catch (InterruptedException ignored) {}
        }).start();
    }

    private void consumeStream(java.io.InputStream in) {
        try {
            byte[] buf = new byte[1024];
            while (in.read(buf) != -1) {}
        } catch (IOException ignored) {}
    }

    // ---------- Rest of the original methods (unchanged) ----------
    private void update() {
        synchronized (lock) {
            activity.getWinHandler().listProcesses();
            final LinearLayout container = findViewById(R.id.LLProcessList);
            if (container.getChildCount() == 0) {
                listAndroidProcess();
                if (container.getChildCount() == 0) {
                    findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                }
            }
        }
        updateCPUInfoView();
        updateMemoryInfoView();
    }

    private void listAndroidProcess() {
        List<ProcessInfo> processInfoList = activity.getTermuxProcessorInfo("0");
        if (processInfoList == null) return;
        int idx = 0;
        for (ProcessInfo processInfo : processInfoList) {
            onGetProcessInfo(idx, processInfoList.size(), processInfo);
            idx++;
        }
    }

    private void showListItemMenu(final View anchorView, final ProcessInfo processInfo) {
        PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);
        listItemMenu.inflate(R.menu.process_popup_menu);
        listItemMenu.setOnMenuItemClickListener((menuItem) -> {
            int itemId = menuItem.getItemId();
            final WinHandler winHandler = activity.getWinHandler();
            if (itemId == R.id.process_affinity) {
                showProcessorAffinityDialog(processInfo);
            } else if (itemId == R.id.bring_to_front) {
                if (processInfo.wow64Process) {
                    winHandler.bringToFront(processInfo.name);
                    dismiss();
                } else {
                    Toast.makeText(activity, "Bring to front not supported for Android processes", Toast.LENGTH_SHORT).show();
                }
            } else if (itemId == R.id.process_end) {
                if (processInfo.wow64Process) {
                    ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process, () ->
                        winHandler.killProcess(processInfo.name));
                } else {
                    ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process, () ->
                        android.os.Process.killProcess(processInfo.pid));
                }
            }
            return true;
        });
        listItemMenu.show();
    }

    private void showProcessorAffinityDialog(final ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(processInfo.getCPUList());
        dialog.setOnConfirmCallback(() -> {
            WinHandler winHandler = activity.getWinHandler();
            winHandler.setProcessAffinity(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()));
            update();
        });
        dialog.show();
    }

    @Override
    public void show() {
        update();
        activity.getWinHandler().setOnGetProcessInfoListener(this);
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.runOnUiThread(TaskManagerDialog.this::update);
            }
        }, 0, 1000);
        super.show();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            synchronized (lock) {
                final LinearLayout container = findViewById(R.id.LLProcessList);
                setBottomBarText(activity.getString(R.string.processes) + ": " + numProcesses);
                if (numProcesses == 0) {
                    container.removeAllViews();
                    findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                    return;
                }
                findViewById(R.id.TVEmptyText).setVisibility(View.GONE);
                int childCount = container.getChildCount();
                View itemView = index < childCount ? container.getChildAt(index) : inflater.inflate(R.layout.process_info_list_item, container, false);
                ((TextView) itemView.findViewById(R.id.TVName)).setText(processInfo.name + (processInfo.wow64Process ? " *32" : ""));
                ((TextView) itemView.findViewById(R.id.TVPID)).setText(String.valueOf(processInfo.pid));
                ((TextView) itemView.findViewById(R.id.TVMemoryUsage)).setText(processInfo.getFormattedMemoryUsage());
                itemView.findViewById(R.id.BTMenu).setOnClickListener((v) -> showListItemMenu(v, processInfo));
                ImageView ivIcon = itemView.findViewById(R.id.IVIcon);
                if (ivIcon != null) ivIcon.setImageResource(R.drawable.taskmgr_process);
                if (index >= childCount) container.addView(itemView);
                if (index == numProcesses - 1 && childCount > numProcesses) {
                    for (int i = childCount - 1; i >= numProcesses; i--) container.removeViewAt(i);
                }
            }
        });
    }

    private void updateCPUInfoView() {
        LinearLayout llCPUInfo = findViewById(R.id.LLCPUInfo);
        llCPUInfo.removeAllViews();
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        int totalClockSpeed = 0;
        short maxClockSpeed = 0;
        for (int i = 0; i < clockSpeeds.length; i++) {
            TextView textView = new TextView(activity);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            short clockSpeed = CPUStatus.getMaxClockSpeed(i);
            textView.setText(clockSpeeds[i] + "/" + clockSpeed + " MHz");
            llCPUInfo.addView(textView);
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short) Math.max(maxClockSpeed, clockSpeed);
        }
        int avgClockSpeed = totalClockSpeed / clockSpeeds.length;
        TextView tvCPUTitle = findViewById(R.id.TVCPUTitle);
        byte cpuUsagePercent = (byte) (((float) avgClockSpeed / maxClockSpeed) * 100.0f);
        tvCPUTitle.setText("CPU (" + cpuUsagePercent + "%)");
    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        byte memUsagePercent = (byte) (((double) usedMem / memoryInfo.totalMem) * 100.0f);
        TextView tvMemoryTitle = findViewById(R.id.TVMemoryTitle);
        tvMemoryTitle.setText(activity.getString(R.string.memory) + " (" + memUsagePercent + "%)");
        TextView tvMemoryInfo = findViewById(R.id.TVMemoryInfo);
        tvMemoryInfo.setText(StringUtils.formatBytes(usedMem, false) + "/" + StringUtils.formatBytes(memoryInfo.totalMem));
    }
}