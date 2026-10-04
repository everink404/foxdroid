plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.foxdroid.app"
    compileSdk = 35
    ndkVersion = "28.1.13356709"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }
    defaultConfig {
        applicationId = "dev.foxdroid.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-a2"
        testInstrumentationRunner = "dev.foxdroid.app.IndexInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":content-model")); implementation(project(":game-core")); implementation(project(":content-local")) }
