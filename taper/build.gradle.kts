plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "dev.taper"
version = "0.1.0"

android {
    namespace = "dev.taper"
    compileSdk = 36

    defaultConfig {
        // minSdk 24: ConnectivityManager.registerDefaultNetworkCallback (used by the
        // sync queue's connectivity trigger) requires API 24, and 24+ covers virtually
        // all budget devices in circulation. See README for the full justification.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.moshi)
    api(libs.okio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Gson is used in tests only, as the DOM-parsing baseline that Taper is compared against.
    testImplementation(libs.gson)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.taper"
            artifactId = "taper"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Taper")
                description.set("On-device memory and reliability SDK for Android apps that integrate AI agents.")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/rsngpt/taper/blob/main/LICENSE")
                    }
                }
            }
        }
    }
}
