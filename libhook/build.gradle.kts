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
    // libxposed API 102(compileOnlyApi:向上游 app 传递为 compileOnly,不进 APK)
    compileOnlyApi("io.github.libxposed:api:102.0.0")
}
