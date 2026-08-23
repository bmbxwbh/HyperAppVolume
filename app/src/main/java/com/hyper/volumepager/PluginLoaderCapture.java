package com.hyper.volumepager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 插件 ClassLoader 捕获器(三锚点,任一成功即可):
 *
 * 锚点A:hook 宿主 PluginInstance$Factory 的公开 create(...) 重载(AOSP 标准路径,
 *        本宿主实测签名:create(Context, ApplicationInfo, ComponentName, Class, PluginListener)),
 *        after 中从返回的 PluginInstance 反射扫描 ClassLoader 字段。
 * 锚点B:hook PluginInstance 全部构造器,同样字段扫描(兜底)。
 * 锚点C:MIUI 专属——PluginInstanceInjector.sClassLoaders(public static final Map)
 *        缓存了全部插件 ClassLoader。反射读取该静态字段会自动触发类初始化,
 *        守护线程轮询 Map 值,对每个新 loader 做"目标类可达性验证"后安装。
 *
 * 捕获判定:testAndInstall() 尝试 loadClass(VolumePanelViewController),
 * 成功才安装,避免误伤其他插件。
 */
public final class PluginLoaderCapture {

    private static final String FACTORY_CLASS =
            "com.android.systemui.shared.plugins.PluginInstance$Factory";
    private static final String INSTANCE_CLASS =
            "com.android.systemui.shared.plugins.PluginInstance";
    private static final String INJECTOR_CLASS =
            "com.miui.systemui.plugin.PluginInstanceInjector";
    private static final String TARGET_CLASS =
            "com.android.systemui.miui.volume.VolumePanelViewController";

    private static final Set<ClassLoader> SEEN = new HashSet<>();
    private static volatile boolean pollStarted = false;

    private PluginLoaderCapture() {}

    public static void install(final ClassLoader hostLoader) {
        // ---------- 锚点A:Factory.create ----------
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
            Logx.i("anchorA hooked Factory.create overloads=" + hooked);
        } catch (Throwable t) {
            Logx.e("anchorA unavailable", t);
        }

        // ---------- 锚点B:PluginInstance 构造器 ----------
        try {
            Class<?> pi = XposedHelpers.findClass(INSTANCE_CLASS, hostLoader);
            for (final Constructor<?> c : pi.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        safeExtract(param.thisObject);
                    }
                });
            }
            Logx.i("anchorB hooked PluginInstance ctors");
        } catch (Throwable t) {
            Logx.e("anchorB unavailable", t);
        }

        // ---------- 锚点C:sClassLoaders 静态缓存轮询 ----------
        try {
            final Class<?> inj = XposedHelpers.findClass(INJECTOR_CLASS, hostLoader);
            startPoll(inj);
            Logx.i("anchorC polling armed");
        } catch (Throwable t) {
            Logx.e("anchorC unavailable", t);
        }
    }

    /**
     * 轮询 MIUI 静态缓存。首次 getStaticObjectField 会自动触发
     * PluginInstanceInjector 的类初始化;VolumePatcher 安装成功后自动退出。
     */
    private static synchronized void startPoll(final Class<?> injectorClass) {
        if (pollStarted) return;
        pollStarted = true;
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000L;
            while (System.currentTimeMillis() < deadline && !VolumePatcher.isInstalled()) {
                try {
                    Object mapObj =
                            XposedHelpers.getStaticObjectField(injectorClass, "sClassLoaders");
                    if (mapObj instanceof Map) {
                        Object[] vals = ((Map<?, ?>) mapObj).values().toArray();
                        for (Object o : vals) {
                            if (o instanceof ClassLoader) {
                                testAndInstall((ClassLoader) o);
                                if (VolumePatcher.isInstalled()) break;
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    break;
                }
            }
            Logx.i("anchorC poll exit, installed=" + VolumePatcher.isInstalled());
        }, "HVP-loader-poll");
        t.setDaemon(true);
        t.start();
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
        boolean ok = false;
        try {
            cl.loadClass(TARGET_CLASS);
            ok = true;
        } catch (Throwable notThisPlugin) {
            Logx.i("loader skipped (not target plugin): " + cl.getClass().getName());
        }
        if (!ok) return;
        Logx.i("*** plugin classloader captured *** -> " + cl.getClass().getName());
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
