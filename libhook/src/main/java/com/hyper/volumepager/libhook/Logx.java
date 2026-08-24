package com.hyper.volumepager.libhook;

import android.util.Log;

/** 统一日志出口,logcat 过滤: HyperVolumePager */
public final class Logx {
    public static final String TAG = "HyperVolumePager";

    private Logx() {}

    public static void i(String msg) { Log.i(TAG, msg); }

    public static void w(String msg) { Log.w(TAG, msg); }

    public static void e(String msg, Throwable t) { Log.e(TAG, msg, t == null ? new Exception("null") : t); }
}
