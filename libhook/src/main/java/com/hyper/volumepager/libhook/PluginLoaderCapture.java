package com.hyper.volumepager.libhook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 插件(miui.systemui.plugin)ClassLoader 捕获器 —— API102 版。
 *
 * 锚点A:hook 宿主 PluginInstance$Factory 的公开 create(...) 重载,
 *        本宿主实测签名:create(Context, ApplicationInfo, ComponentName, Class, PluginListener),
 *        从返回的 PluginInstance 反射扫描 ClassLoader 字段。
 * 锚点B:hook PluginInstance 全部构造器,同样字段扫描(兜底)。
 * 锚点C:MIUI 专属——PluginInstanceInjector.sClassLoaders(public static Map)
 *        缓存全部插件 loader;反射轮询该 Map(读取会自动触发类初始化)。
 *
 * 捕获判定:候选 loader 必须 loadClass 成功 VolumePanelViewController 才安装。
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

    public static void install(final XposedInterface xp, final ClassLoader hostLoader) {
        // ---------- 锚点A:Factory.create ----------
        int hooked = 0;
        try {
            Class<?> factory = hostLoader.loadClass(FACTORY_CLASS);
            for (final Method m : factory.getDeclaredMethods()) {
                if (!"create".equals(m.getName())) continue;
                if (!Modifier.isPublic(m.getModifiers())) continue;
                xp.hook(m).intercept(chain -> {
                    Object result;
                    try {
                        result = chain.proceed();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                    safeExtract(result);
                    return result;
                });
                hooked++;
            }
            Logx.i("anchorA hooked Factory.create overloads=" + hooked);
        } catch (Throwable t) {
            Logx.e("anchorA unavailable", t);
        }

        // ---------- 锚点B:PluginInstance 构造器 ----------
        int ctors = 0;
        try {
            Class<?> pi = hostLoader.loadClass(INSTANCE_CLASS);
            for (final var c : pi.getDeclaredConstructors()) {
                xp.hook(c).intercept(chain -> {
                    Object result;
                    try {
                        result = chain.proceed();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                    // 构造器:after 阶段 thisObject 即已初始化的实例
                    safeExtract(chain.getThisObject());
                    return result;
                });
                ctors++;
            }
            // 构造器场景:thisObject 在 before 阶段为未初始化对象,after 后才有效;
            // 因此改从 args 无法取,直接在 after 用 chain.getThisObject():
            Logx.i("anchorB hooked PluginInstance ctors=" + ctors);
        } catch (Throwable t) {
            Logx.e("anchorB unavailable", t);
        }

        // ---------- 锚点C:sClassLoaders 轮询 ----------
        try {
            startPoll(hostLoader.loadClass(INJECTOR_CLASS));
            Logx.i("anchorC polling armed");
        } catch (Throwable t) {
            Logx.e("anchorC unavailable", t);
        }
    }

    private static synchronized void startPoll(final Class<?> injectorClass) {
        if (pollStarted) return;
        pollStarted = true;
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000L;
            while (System.currentTimeMillis() < deadline && !VolumePatcher.isInstalled()) {
                try {
                    Field f = injectorClass.getDeclaredField("sClassLoaders");
                    f.setAccessible(true);
                    Object mapObj = f.get(null);
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
            VolumePatcher.install(VolumePatcher.xp(), cl);
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
