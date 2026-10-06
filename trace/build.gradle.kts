plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.binary.compatibility.validator)
}

group = "io.usetrace"
version = "0.1.0"

// The artefact a customer declares, rather than the module name.
base.archivesName.set("trace-sdk-android")

android {
    namespace = "io.usetrace.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        // The User-Agent the transport sends is built from this, and the server's bot guard drops an empty one.
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric needs the merged resources and manifest to start a Context.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.installreferrer)
    // DELIBERATE VIOLATION, reverted in the next commit: a second runtime dependency.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
