package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Stub */
public abstract class XC_MethodHook {

    protected XC_MethodHook() {}

    @SuppressWarnings("unused")
    protected XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    @SuppressWarnings("unused")
    public static final class Unhook {
        private final Member hookMethod;
        private Unhook(Member hookMethod) { this.hookMethod = hookMethod; }
        public Member getHookMethod() { return hookMethod; }
        public void unhook() { }
    }

    /**
     * 字段语义与 LSPosed 传统桥一致:result/throwable 为公共字段,
     * 同时提供 getResult/setResult 等访问器,兼容两种模块写法。
     */
    public static class MethodHookParam<T> {
        /** The hooked method/constructor. */
        public Member method;
        /** The this reference, or null for static methods. */
        public T thisObject;
        /** Arguments of the call. */
        public Object[] args;

        public Object result = null;
        public Throwable throwable = null;
        public boolean returnEarly = false;

        public Object getResult() { return result; }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public boolean hasThrowable() { return throwable != null; }
    }
}
