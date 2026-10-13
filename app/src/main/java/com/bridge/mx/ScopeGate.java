package com.bridge.mx;

import java.util.Set;

/**
 * Pure decision helper for the Download-only observation gate
 * (JVM-testable, no Android dependencies).
 *
 * <p>The bridge is only permitted to track videos under the Download tree.
 * Accessibility observations expose only a display title — no path — so an
 * observed row is admitted only when its canonical key also exists in the
 * Download library allowlist produced by {@link LibraryScanner}. Until the
 * allowlist is known (empty set) the gate is lenient: nothing is dropped.
 */
public final class ScopeGate {

    private ScopeGate() {
    }

    /**
     * True when an a11y-observed row must be dropped: the Download allowlist
     * is known (non-empty) and the row's canonical key is not in it.
     */
    public static boolean outside(Set<String> allowedKeys, String key) {
        if (allowedKeys == null || allowedKeys.isEmpty()) return false; // allowlist unknown yet
        return key == null || !allowedKeys.contains(key);
    }
}