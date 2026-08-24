package de.robv.android.xposed;

/** Stub */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam);
}
