plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.foxdroid.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.foxdroid.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-a1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":content-model")); implementation(project(":game-core")); implementation(project(":content-local")) }
