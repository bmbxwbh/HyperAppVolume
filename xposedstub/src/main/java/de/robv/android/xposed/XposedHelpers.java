package de.robv.android.xposed;

/** Stub(仅覆盖本模块用到的签名;方法体永不执行,编译期只看签名) */
public final class XposedHelpers {

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static void findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static void findAndHookMethod(Class<?> clazz,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    @SuppressWarnings("unused")
    public static Object callMethod(Object receiver, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }
}
