// 纯 Java 库:仅参与编译(classpath),不会被打进 APK
// 运行时由 LSPosed 提供真实的 de.robv.android.xposed.* 实现
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
