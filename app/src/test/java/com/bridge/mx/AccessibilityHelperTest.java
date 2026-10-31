package com.bridge.mx;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccessibilityHelperTest {

    private static final String PKG = "com.bridge.mx";
    private static final String CLS = "com.bridge.mx.MxService";

    @Test
    public void acceptsFullFlattenedComponent() {
        assertTrue(AccessibilityHelper.hasComponent(
                "com.bridge.mx/com.bridge.mx.MxService", PKG, CLS));
    }

    @Test
    public void acceptsShortFlattenedComponent() {
        // This is the form that made the old `contains(pkg + "/" + cls)` check
        // report a perfectly enabled service as off.
        assertTrue(AccessibilityHelper.hasComponent(
                "com.bridge.mx/.MxService", PKG, CLS));
    }

    @Test
    public void acceptsComponentInsideColonList() {
        String enabled = "com.android.talkback/com.google.android.marvin.talkback.TalkBackService"
                + ":com.bridge.mx/.MxService:com.example/.OtherService";
        assertTrue(AccessibilityHelper.hasComponent(enabled, PKG, CLS));
    }

    @Test
    public void rejectsLookalikeClassName() {
        assertFalse(AccessibilityHelper.hasComponent(
                "com.bridge.mx/com.bridge.mx.MxServiceExtra", PKG, CLS));
    }

    @Test
    public void rejectsWrongPackage() {
        assertFalse(AccessibilityHelper.hasComponent(
                "com.other/com.other.MxService", PKG, CLS));
    }

    @Test
    public void rejectsNullAndEmpty() {
        assertFalse(AccessibilityHelper.hasComponent(null, PKG, CLS));
        assertFalse(AccessibilityHelper.hasComponent("", PKG, CLS));
        assertFalse(AccessibilityHelper.hasComponent("com.bridge.mx/.MxService",
                null, CLS));
    }
}
