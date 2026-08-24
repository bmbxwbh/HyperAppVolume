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
        versionCode = 4
        versionName = "1.2.0"
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

    packaging {
        resources {
            // 保证 META-INF/xposed/*(java_init.list/scope.list/module.prop)完整进入 APK
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // libxposed 现代API(compileOnly,运行时由管理器注入实现)
    compileOnly("io.github.libxposed:api:102.0.0")
}
