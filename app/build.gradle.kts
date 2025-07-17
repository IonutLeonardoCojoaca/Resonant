plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("androidx.navigation.safeargs.kotlin")
}

android {
    namespace = "com.example.resonant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.resonant"
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "1.33.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    viewBinding {
        enable = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-firestore-ktx")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("com.google.firebase:firebase-storage-ktx")

    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")

    implementation ("com.google.firebase:firebase-config-ktx:21.0.1")
    implementation ("com.google.firebase:firebase-analytics")

    implementation ("androidx.media:media:1.7.0")

    implementation ("androidx.paging:paging-runtime:3.1.1")

    implementation ("com.airbnb.android:lottie:6.0.0")

    implementation ("com.google.android.material:material:1.12.0")

    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation ("androidx.core:core-ktx:1.7.0")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation ("com.google.firebase:firebase-messaging:23.0.5")

    implementation ("androidx.navigation:navigation-fragment:2.9.0")
    implementation ("androidx.navigation:navigation-ui:2.9.0")
    implementation ("com.google.android.material:material:1.12.0")

    implementation (libs.picasso)

    implementation ("com.facebook.shimmer:shimmer:0.5.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation ("com.github.bumptech.glide:glide:4.16.0")



}