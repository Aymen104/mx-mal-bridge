package com.bridge.mx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Tests for the Download-only observation gate (ScopeGate). */
public class ScopeGateTest {

    private static Set<String> keys(String... k) {
        return new HashSet<>(Arrays.asList(k));
    }

    @Test
    public void unknownAllowlistIsLenient() {
        // Before the first Download scan the gate must not drop anything.
        assertFalse(ScopeGate.outside(null, "ep:foo|1"));
        assertFalse(ScopeGate.outside(new HashSet<>(), "ep:foo|1"));
    }

    @Test
    public void observedKeyInsideDownloadIsAllowed() {
        Set<String> allowed = keys("ep:tensei|7", "ep:goblin|2");
        assertFalse(ScopeGate.outside(allowed, "ep:tensei|7"));
    }

    @Test
    public void observedKeyOutsideDownloadIsDropped() {
        // An SD-card row (Uchi no Shishou) must never enter the store.
        Set<String> allowed = keys("ep:tensei|7");
        assertTrue(ScopeGate.outside(allowed, "ep:uchinoshishou|5"));
    }

    @Test
    public void nullKeyIsDroppedWhenAllowlistKnown() {
        assertTrue(ScopeGate.outside(keys("ep:tensei|7"), null));
    }
}