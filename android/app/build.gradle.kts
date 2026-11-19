plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "herdr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.javamomma.herdr"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Release builds are signed with the private release key (RELEASING.md): the
    // file named by HERDR_KEYSTORE and its password. There is no fallback to the
    // debug key; a release build without them fails (see requireReleaseKey).
    val keystore = System.getenv("HERDR_KEYSTORE")
    signingConfigs {
        // One shared debug key (committed, as is usual: it signs only test
        // builds), so each CI build installs over the last without an uninstall.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("HERDR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HERDR_KEY_ALIAS") ?: "herdr"
                keyPassword = System.getenv("HERDR_KEY_PASSWORD") ?: System.getenv("HERDR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
            "META-INF/{AL2.0,LGPL2.1}",
        )
    }
}

// Fails only when a release variant is actually built; configuring the project,
// tests and assembleDebug need no secrets.
val requireReleaseKey = tasks.register("requireReleaseKey") {
    doLast {
        val file = System.getenv("HERDR_KEYSTORE")
        val pass = System.getenv("HERDR_KEYSTORE_PASSWORD")
        if (file.isNullOrBlank() || pass.isNullOrBlank()) {
            throw GradleException(
                "Release builds are signed with the private release key. Set HERDR_KEYSTORE (path to the keystore file), " +
                    "HERDR_KEYSTORE_PASSWORD and optionally HERDR_KEY_ALIAS (default herdr). See android/RELEASING.md. " +
                    "There is no fallback to the debug key.",
            )
        }
        if (!file(file).isFile) throw GradleException("HERDR_KEYSTORE points to a file that does not exist.")
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") dependsOn(requireReleaseKey)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":protocol"))
    implementation(project(":terminal"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // SSH to the Mac; BouncyCastle for Ed25519 and modern key exchange on Android.
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
}
