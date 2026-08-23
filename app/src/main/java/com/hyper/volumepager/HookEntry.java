package com.hyper.volumepager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_LoadPackage.LoadPackageParam;

/**
 * 入口:仅在 SystemUI 进程内工作。
 * 插件(miui.systemui.plugin)的类不在宿主主 ClassLoader 中,
 * 此处只负责安装"插件 ClassLoader 捕获器",真正的 hook 在捕获成功后由 VolumePatcher 安装。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String HOST_PACKAGE = "com.android.systemui";

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        if (!HOST_PACKAGE.equals(lpp.packageName)) return;
        Logx.i("attached to " + HOST_PACKAGE + " (" + lpp.processName + ")");
        try {
            PluginLoaderCapture.install(lpp.classLoader);
        } catch (Throwable t) {
            Logx.e("capture install failed", t);
        }
    }
}
