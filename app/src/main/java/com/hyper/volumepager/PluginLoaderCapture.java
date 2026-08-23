package com.hyper.volumepager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 插件 ClassLoader 捕获器。
 *
 * 目标类 com.android.systemui.miui.volume.* 由 miui.systemui.plugin 提供,
 * 运行在 SystemUI 进程的独立 PathClassLoader 中。本类通过两个互补锚点捕获它:
 *
 * 方案A(主):hook 宿主 PluginInstance$Factory 的所有公开 create(...) 重载,
 *            after 中从返回的 PluginInstance 实例反射扫描 ClassLoader 字段;
 * 方案B(兜底):hook PluginInstance 的全部构造器,做同样的字段扫描。
 *
 * 捕获到的候选 loader 必须能成功 loadClass 目标类才安装 hook,避免误伤其他插件。
 */
public final class PluginLoaderCapture {

    private static final String FACTORY_CLASS =
            "com.android.systemui.shared.plugins.PluginInstance$Factory";
    private static final String INSTANCE_CLASS =
            "com.android.systemui.shared.plugins.PluginInstance";
    private static final String TARGET_CLASS =
            "com.android.systemui.miui.volume.VolumePanelViewController";

    /** 已处理过的 loader(无论成败,避免反复试加载) */
    private static final Set<ClassLoader> SEEN = new HashSet<>();

    private PluginLoaderCapture() {}

    public static void install(final ClassLoader hostLoader) {
        // ---------- 方案A:Factory.create ----------
        int hooked = 0;
        try {
            Class<?> factory = XposedHelpers.findClass(FACTORY_CLASS, hostLoader);
            for (final Method m : factory.getDeclaredMethods()) {
                if (!"create".equals(m.getName())) continue;
                if (!Modifier.isPublic(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        safeExtract(param.getResult());
                    }
                });
                hooked++;
            }
            Logx.i("factory create overloads hooked = " + hooked);
        } catch (Throwable t) {
            Logx.e("factory capture unavailable", t);
        }

        // ---------- 方案B:PluginInstance 构造器 ----------
        int ctors = 0;
        try {
            Class<?> pi = XposedHelpers.findClass(INSTANCE_CLASS, hostLoader);
            for (final Constructor<?> c : pi.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        safeExtract(param.thisObject);
                    }
                });
                ctors++;
            }
            Logx.i("PluginInstance constructors hooked = " + ctors);
        } catch (Throwable t) {
            Logx.e("instance capture unavailable", t);
        }

        if (hooked == 0 && ctors == 0) {
            Logx.w("no capture anchor available; module will stay idle");
        }
    }

    // ------------------------------------------------------------------

    private static void safeExtract(Object pluginInstance) {
        if (pluginInstance == null) return;
        try {
            for (Field f : allFields(pluginInstance.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!ClassLoader.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object o = f.get(pluginInstance);
                if (o instanceof ClassLoader) {
                    testAndInstall((ClassLoader) o);
                }
            }
        } catch (Throwable t) {
            Logx.e("extract loader failed", t);
        }
    }

    private static void testAndInstall(ClassLoader cl) {
        if (cl == null || !SEEN.add(cl)) return;
        try {
            cl.loadClass(TARGET_CLASS);
        } catch (Throwable notThisPlugin) {
            return; // 其他插件的 loader,静默跳过
        }
        Logx.i("plugin classloader captured -> " + cl.getClass().getName());
        try {
            VolumePatcher.install(cl);
        } catch (Throwable t) {
            Logx.e("VolumePatcher.install failed", t);
        }
    }

    private static Field[] allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Collections.addAll(out, k.getDeclaredFields());
        }
        return out.toArray(new Field[0]);
    }
}
