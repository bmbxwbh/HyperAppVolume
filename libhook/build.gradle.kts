plugins {
    id("com.android.library")
}

android {
    namespace = "com.hyper.volumepager.libhook"
    compileSdk = 34

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // libxposed API 102(compileOnly:运行时由管理器注入实现,不进 APK)
    compileOnly("io.github.libxposed:api:102.0.0")
}
