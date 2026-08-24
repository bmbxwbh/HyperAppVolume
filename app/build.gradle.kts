plugins {
    id("com.android.application")
}

android {
    namespace = "com.hyper.volumepager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hyper.volumepager"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Xposed API 以源码桩形式内置于 :xposedstub(compileOnly,不打进 APK)
    // 运行时由 LSPosed 注入真实实现;不再依赖外部 maven 构件
    compileOnly(project(":xposedstub"))
}
