plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Face model is shared with the Python prototype: <repo>/models/face_landmarker.task
val modelsDir = rootProject.file("../models")

android {
    namespace = "com.rppg.vitals"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rppg.vitals"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // phones only (drops x86 emulator libraries, ~halves the APK)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Demo app: release is signed with the debug key so it can be side-loaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(modelsDir)
    androidResources {
        noCompress += "task"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val checkModel by tasks.registering {
    doLast {
        if (!File(modelsDir, "face_landmarker.task").isFile) {
            throw GradleException(
                "Missing ${modelsDir}/face_landmarker.task - run: python python/tools/download_model.py"
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkModel) }

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation("junit:junit:4.13.2")
}
