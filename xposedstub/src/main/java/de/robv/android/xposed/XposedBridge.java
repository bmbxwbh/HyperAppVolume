package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Stub */
public final class XposedBridge {

    private XposedBridge() {}

    @SuppressWarnings("unused")
    public static void log(String text) { }

    @SuppressWarnings("unused")
    public static void log(Throwable t) { }

    @SuppressWarnings("unused")
    public static Object hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }
}
