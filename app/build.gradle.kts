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
        versionCode = 16
        versionName = "1.5.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    implementation(project(":libhook"))
    // 供 app 层 R8 解析 libhook 中对 libxposed API 的引用(compileOnly 不进 APK)
    compileOnly("io.github.libxposed:api:102.0.0")
}
