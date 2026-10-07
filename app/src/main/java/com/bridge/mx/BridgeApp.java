package com.bridge.mx;

import android.app.Application;
import android.os.Build;

/**
 * Initializes the error report file and captures uncaught exceptions from any
 * thread so crashes leave a report behind instead of vanishing into logcat.
 */
public class BridgeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Report.init(this);
        Report.info("session", "app start, sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL);

        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Report.err("uncaught", "thread=" + thread.getName(), throwable);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(thread, throwable);
        });
    }
}
