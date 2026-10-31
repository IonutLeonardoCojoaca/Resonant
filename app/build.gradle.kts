plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("androidx.navigation.safeargs.kotlin")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("kotlin-kapt")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.example.resonant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.resonant"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "4.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    viewBinding {
        enable = true
    }

    // Firma de la variante release para poder instalarla y probarla en el móvil (Build Variants > release + Run,
    // o assembleRelease + adb install). Usa el mismo debug.keystore de Android Studio con el que ya se firma el
    // debug, así el inicio de sesión con Google sigue funcionando (es la SHA-1 registrada en Firebase) y el APK
    // se instala encima del debug sin desinstalar. Para publicar en Play se sigue usando
    // Build > Generate Signed Bundle / APK con la keystore real, que ignora este bloque.
    val localTestingKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
    signingConfigs {
        create("localTesting") {
            storeFile = localTestingKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (localTestingKeystore.exists()) signingConfig = signingConfigs.getByName("localTesting")
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
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation("androidx.fragment:fragment-testing-manifest:1.8.9")
    androidTestImplementation("androidx.fragment:fragment-testing:1.8.9")

    // Firebase: solo lo que se usa (analytics y auth). Database, Firestore, Storage, Remote Config y Messaging no
    // tenían ningún uso en el código ni en el manifest y arrancaban sus inicializadores en cada apertura.
    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    // GoogleSignInClient clásico: respaldo cuando el selector de Credential Manager no se muestra (HyperOS)
    implementation("com.google.android.gms:play-services-auth:21.1.1")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")


    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")

    implementation("com.google.code.gson:gson:2.10.1")




    implementation ("com.airbnb.android:lottie:6.0.0")

    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")


    implementation ("androidx.navigation:navigation-fragment:2.9.0")
    implementation ("androidx.navigation:navigation-ui:2.9.0")
    implementation ("com.google.android.material:material:1.13.0")

    implementation ("com.facebook.shimmer:shimmer:0.5.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.signalr:signalr:8.0.30")
    implementation("com.google.zxing:core:3.5.3")

    implementation ("com.github.bumptech.glide:glide:4.16.0")
    // Glide descarga con el OkHttpClient de la app y genera el AppGlideModule (utils/ResonantGlideModule).
    implementation ("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")

    implementation("androidx.core:core-splashscreen:1.0.1")
    // Baseline Profile: instala el perfil generado por :baselineprofile (arranque y scroll precompilados en release).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation("androidx.datastore:datastore-preferences:1.0.0")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-database:$media3Version")
    implementation("androidx.media3:media3-exoplayer-workmanager:$media3Version")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("com.google.android.flexbox:flexbox:3.0.0")

}
