plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.taper.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.taper.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The whole point: the demo consumes Taper exactly like a third-party app would.
    implementation(project(":taper"))
    implementation(libs.kotlinx.coroutines.android)
}
