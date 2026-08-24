package com.hyper.volumepager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_LoadPackage.LoadPackageParam;

/**
 * 路径探针:仅当框架按「传统声明」(assets/xposed_init)加载模块时才会被实例化。
 * 用于判定当前框架消费的是哪一种入口声明。
 */
public class LegacyProbe implements IXposedHookLoadPackage {

    static {
        Logx.i("probe: LEGACY path fired (assets/xposed_init consumed)");
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        if (!"com.android.systemui".equals(lpp.packageName)) return;
        Logx.i("legacy handleLoadPackage: " + lpp.packageName
                + " / process=" + lpp.processName);
    }
}
