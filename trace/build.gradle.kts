import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish)
}

group = "io.usetrace"
// The one place the version is set. A change to it is a release: it needs release-approved, and the merge publishes it
// (CLAUDE.md, Releasing). The README's dependency line has to match, which scripts/version.sh checks.
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// The release AAR with its sources and javadoc jars, signed, to Maven Central through the Central Portal. The POM's
// name, description, licence, developer and scm are the POM_ lines in gradle.properties.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(group.toString(), "trace-sdk-android", version.toString())
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
}
