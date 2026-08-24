// 仅编译期使用的传统桥桩(诊断双入口用;不打进APK)
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
