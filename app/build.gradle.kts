plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.facetracking3d"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.facetracking3d"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // CameraX Libraries
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Google ML Kit
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.face.mesh.detection)
    implementation(libs.mlkit.segmentation.selfie)

    // 3D Render Motoru
    implementation("io.github.sceneview:sceneview:2.2.1")
}