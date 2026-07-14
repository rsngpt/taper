plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.taper.benchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.taper.benchmark"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Orchestrator: each benchmark test runs in a fresh process, so heap state
        // cannot leak between measurements and an OOM only kills that one test.
        testInstrumentationRunnerArguments["clearPackageData"] = "false"
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
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
    implementation(project(":taper"))
    // DOM-parsing baselines under measurement: org.json ships with Android; Gson tree mode is added here.
    implementation(libs.gson)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestUtil(libs.androidx.test.orchestrator)
}
