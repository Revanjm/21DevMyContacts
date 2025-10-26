plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") // Добавьте эту строку
}

android {
    namespace = "com.ppnkdeapp.mycontacts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ppnkdeapp.mycontacts"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Firebase с BoM
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    // UI Components
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Compose дополнительные зависимости
    implementation("androidx.compose.runtime:runtime-livedata:1.6.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.compose.material:material:1.6.0")
    implementation("androidx.compose.material:material-icons-core:1.6.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Accompanist
    implementation("com.google.accompanist:accompanist-insets:0.31.5-beta")
    implementation("com.google.accompanist:accompanist-insets-ui:0.31.5-beta")

    // WebRTC для VoIP
    implementation("io.getstream:stream-webrtc-android:1.0.0")

    // Socket.IO для сигнального сервера
    implementation("io.socket:socket.io-client:2.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Storage
    implementation("com.tencent:mmkv:1.3.4")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.31.5-beta")
    implementation(libs.androidx.lifecycle.process)
//    implementation(libs.firebase.messaging.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}