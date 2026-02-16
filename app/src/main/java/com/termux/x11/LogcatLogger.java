package com.termux.x11;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Process;   // Android Process (PID)

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

public class LogcatLogger {

    private static Thread loggerThread;
    private static boolean running = false;
    private static java.lang.Process logcatProcess; // keep reference to destroy later

    public static void start(Context context) {
        if (running) return;
        running = true;

        int myPid = Process.myPid(); // android.os.Process

        loggerThread = new Thread(() -> {
            try {
                File dir;
                if (Build.VERSION.SDK_INT >= 29) {
                    dir = new File(context.getExternalFilesDir(null), "logs");
                } else {
                    dir = new File(Environment.getExternalStorageDirectory(), "xodos/logs");
                }
                if (!dir.exists()) dir.mkdirs();

                File logFile = new File(dir, "app.log");
                FileWriter writer = new FileWriter(logFile, true);

                // Clear logcat buffer (using ProcessBuilder for safety)
                new ProcessBuilder("logcat", "-c").start().waitFor();

                // Use sh -c to run logcat (allows complex commands if needed)
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", "logcat -v time");
                pb.redirectErrorStream(true); // merge stderr to avoid blocking
                logcatProcess = pb.start();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream())
                );

                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.contains(String.valueOf(myPid))) {
                        writer.write(line + "\n");
                        writer.flush();
                    }
                }

                writer.close();

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (logcatProcess != null) {
                    logcatProcess.destroy();
                    logcatProcess = null;
                }
            }
        });

        loggerThread.start();
    }

    public static void stop() {
        running = false;
        if (loggerThread != null) {
            loggerThread.interrupt();
            loggerThread = null;
        }
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
    }
}