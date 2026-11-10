package com.bridge.mx;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
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

    /** True when this app may write secure settings (granted once over adb). */
    static boolean canWriteSecure(Context c) {
        if (c == null) return false;
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ensure THIS app's accessibility service is enabled, adding it while
     * preserving every other service the user or another app has turned on.
     *
     * <p>Android releases an accessibility service when the phone goes idle
     * with the screen off (realme does this while the device is offline), and it
     * does not rebind it by itself even after the network returns - that is the
     * "the app never came back" report. With WRITE_SECURE_SETTINGS the setting
     * can be restored directly, and the system rebinds the service as soon as
     * the value changes. Without the permission this is a no-op, and the caller
     * falls back to telling the user (see MxService's notification).
     *
     * @return true when our component is enabled afterwards.
     */
    public static boolean ensureEnabled(Context c) {
        if (c == null) return false;
        Context app = c.getApplicationContext();
        if (app == null) app = c;
        if (!canWriteSecure(app)) return isEnabled(app);   // nothing we can do
        try {
            String me = app.getPackageName() + "/" + MxService.class.getName();
            ContentResolver cr = app.getContentResolver();
            String cur = Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (hasComponent(cur, app.getPackageName(), MxService.class.getName())) {
                Settings.Secure.putInt(cr, "accessibility_enabled", 1);
                return true;
            }
            StringBuilder sb = new StringBuilder();
            if (cur != null) {
                for (String token : cur.split(":")) {
                    String s = token.trim();
                    if (s.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(':');
                    sb.append(s);
                }
            }
            if (sb.length() > 0) sb.append(':');
            sb.append(me);
            Settings.Secure.putString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
            Settings.Secure.putInt(cr, "accessibility_enabled", 1);
            Report.info("service", "accessibility re-enabled automatically to '"
                    + me + "' (was: " + cur + ")");
            return true;
        } catch (Throwable t) {
            Report.warn("service", "ensureEnabled failed: " + t.getMessage());
            return isEnabled(app);
        }
    }
}
