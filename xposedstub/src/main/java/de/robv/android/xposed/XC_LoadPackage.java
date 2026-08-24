package de.robv.android.xposed;

/** Stub */
public abstract class XC_LoadPackage implements IXposedMod {

    public LoadPackageParam param;

    protected XC_LoadPackage() {}

    protected abstract void handleLoadPackage(LoadPackageParam lpparam);

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;

        public LoadPackageParam() {}
    }
}
