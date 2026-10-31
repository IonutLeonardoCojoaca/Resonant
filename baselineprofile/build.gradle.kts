plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

// Módulo de pruebas que genera el Baseline Profile de :app (arranque + Explorar + Playmix) con Macrobenchmark.
// Generar: .\gradlew.bat :app:generateBaselineProfile  (emulador AOSP sin Play o dispositivo API 33+ conectado).
// El resultado se copia a app/src/main/baseline-prof.txt y lo instala profileinstaller en release.
android {
    namespace = "com.example.resonant.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Se genera en el dispositivo/emulador conectado; sin Gradle Managed Devices para no añadir descargas al build.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
