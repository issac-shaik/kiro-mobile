plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "dev.kiromobile.chat"; compileSdk = 35; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" } }
dependencies { implementation(project(":core:design")); implementation(project(":core:connection")); implementation(project(":feature:notifications")); implementation("com.journeyapps:zxing-android-embedded:4.3.0"); implementation("io.noties.markwon:core:4.6.2") }
