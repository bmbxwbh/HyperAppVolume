# === libxposed API 102 官方推荐规则 ===
# 来源: https://github.com/libxposed/api#for-module-developers
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep,allowshrinking,allowoptimization,allowobfuscation class ** implements io.github.libxposed.api.XposedInterface$Hooker

# 本模块大量使用按名称反射,整体保留
-keep class com.hyper.volumepager.libhook.** { *; }
