package com.hyper.volumepager;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * libxposed API 102 入口(注册于 META-INF/xposed/java_init.list)。
 * 作用域由 META-INF/xposed/scope.list 声明(com.android.systemui)。
 */
public class ModernEntry extends XposedModule {

    static {
        // 探针1:类被JVM加载即打印(与框架回调无关)
        Logx.i("probe: ModernEntry class initialized");
    }

    public ModernEntry() {
        // 探针2:实例化成功即打印
        Logx.i("probe: ModernEntry instantiated");
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 探针3:框架生命周期回调到达
        Logx.i("entry loaded: process=" + param.getProcessName()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) return;
        Logx.i("SystemUI ready, installing volume pager hooks");
        try {
            PluginLoaderCapture.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Logx.e("install failed", t);
        }
    }
}
