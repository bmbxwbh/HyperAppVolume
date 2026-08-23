package de.robv.android.xposed;

/** Stub */
public abstract class XC_LoadPackage implements IXposedMod {

    public LoadPackageParam param;

    protected XC_LoadPackage() {}

    protected abstract void handleLoadPackage(LoadPackageParam lpparam);

    public static final class LoadPackageParam {
        /** The name of the package being loaded. */
        public String packageName;
        /** The name of the process in which the package is loaded. */
        public String processName;
        /** The ClassLoader used to load the package. */
        public ClassLoader classLoader;
        /** Whether the package is loaded for the first time. */
        public boolean isFirstApplication;

        public LoadPackageParam() {}
    }
}
