plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.kiromobile.app"
    compileSdk = 35
    defaultConfig { applicationId = providers.gradleProperty("kiroApplicationId").getOrElse("dev.kiromobile.app"); minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "0.3.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional fork-owned Firebase project. No account identifiers are committed.
        resValue("string", "firebase_app_id", providers.gradleProperty("firebaseAppId").getOrElse(""))
        resValue("string", "firebase_api_key", providers.gradleProperty("firebaseApiKey").getOrElse(""))
        resValue("string", "firebase_project_id", providers.gradleProperty("firebaseProjectId").getOrElse(""))
        resValue("string", "firebase_sender_id", providers.gradleProperty("firebaseSenderId").getOrElse(""))
    }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":feature:chat")); implementation(project(":feature:notifications"))
    androidTestImplementation(project(":core:connection"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
