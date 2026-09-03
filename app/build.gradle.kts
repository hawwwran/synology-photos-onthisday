plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hawwwran.photosonthisday"
    // compileSdk 36 to satisfy current AndroidX; targetSdk 35 matches the test device.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hawwwran.photosonthisday"
        // 29: MediaStore saves need no storage permission, and the platform blocks cleartext on
        // every supported level (decision 004, amended 2026-09-03). Household phones are newer.
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "OnThisDay-$versionName")
    }

    signingConfigs {
        create("release") {
            // Keystore stays out of git; credentials come from ~/.gradle/gradle.properties.
            val ks = rootProject.file("keystore.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = (project.findProperty("OTD_KEYSTORE_PASSWORD") as? String) ?: ""
                keyAlias = (project.findProperty("OTD_KEY_ALIAS") as? String) ?: "onthisday"
                keyPassword = (project.findProperty("OTD_KEY_PASSWORD") as? String) ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Co-sign with the release keystore when present so installDebug
            // can upgrade a release install in place. No-op without keystore.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // From minSdk 28 AGP packages dex uncompressed (Android runs it in place, saving the install-time
    // copy), which tripled the APK from 24 to 74 MB. The APK travels over GitHub and the in-app
    // updater on a phone connection, so the download size wins: keep dex compressed as before.
    packaging {
        dex { useLegacyPackaging = true }
    }

    // Room exports the schema to app/schemas so a future migration has a baseline to diff.
    // The androidTest source set reads it back to verify migrations on a device.
    sourceSets {
        getByName("androidTest").assets.srcDir(layout.projectDirectory.dir("schemas"))
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver3.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.tooling)
}
