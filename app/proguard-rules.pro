# 模块不混淆(hook 目标由运行时反射定位)。
# 若未来开启 minifyEnabled,以下为 libxposed API 102 官方推荐规则:
# 来源: https://github.com/libxposed/api#for-module-developers
# -dontwarn io.github.libxposed.annotation.**
# -adaptresourcefilecontents META-INF/xposed/java_init.list
# -keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
#     public <init>();
# }
# -keep,allowshrinking,allowoptimization,allowobfuscation class ** implements io.github.libxposed.api.XposedInterface$Hooker
