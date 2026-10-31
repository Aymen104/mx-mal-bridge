package com.bridge.mx;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Whether this app's accessibility service is enabled.
 *
 * <p>The old UI check searched for {@code pkg/com.bridge.mx.MxService}. Android
 * writes the enabled list as a flattened {@link ComponentName}, often in the
 * short form {@code pkg/.MxService}, so that test was false for a perfectly
 * enabled service. Use the AccessibilityManager list first (authoritative) and
 * keep the settings string as a fallback for ROMs that lazily publish it.
 */
public final class AccessibilityHelper {

    private AccessibilityHelper() {
    }

    public static boolean isEnabled(Context c) {
        if (c == null) return false;
        Context app = c.getApplicationContext();
        if (app == null) app = c;
        String pkg = app.getPackageName();
        String cls = MxService.class.getName();

        try {
            AccessibilityManager am = (AccessibilityManager)
                    app.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        ResolveInfo ri = info == null ? null : info.getResolveInfo();
                        if (ri == null || ri.serviceInfo == null) continue;
                        if (ri.serviceInfo.packageName == null
                                || ri.serviceInfo.name == null) continue;
                        ComponentName cn = new ComponentName(
                                ri.serviceInfo.packageName, ri.serviceInfo.name);
                        if (pkg.equals(cn.getPackageName())
                                && cls.equals(cn.getClassName())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the settings copy.
        }

        try {
            String enabled = Settings.Secure.getString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return hasComponent(enabled, pkg, cls);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Exact, token-based match of a component in the colon-separated secure
     * setting. Accepts both {@code pkg/full.Class} and {@code pkg/.Class}.
     */
    static boolean hasComponent(String enabled, String pkg, String cls) {
        if (enabled == null || pkg == null || cls == null) return false;
        String full = pkg + "/" + cls;
        String shortForm = pkg + "/." + (cls.startsWith(pkg + ".")
                ? cls.substring(pkg.length() + 1) : cls);
        for (String token : enabled.split(":")) {
            String s = token.trim();
            if (s.isEmpty()) continue;
            if (s.equals(full) || s.equals(shortForm)) return true;
            // Be liberal about OEMs that write the class with a leading dot or
            // the package repeated after the slash.
            int slash = s.indexOf('/');
            if (slash <= 0) continue;
            String p = s.substring(0, slash);
            String k = s.substring(slash + 1);
            if (!p.equals(pkg)) continue;
            if (k.equals(cls) || k.equals("." + cls)) return true;
        }
        return false;
    }
}
