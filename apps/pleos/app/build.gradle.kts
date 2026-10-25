plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "camp.room.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "camp.room.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "runtime"
    productFlavors {
        create("demo") {
            dimension = "runtime"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("campApiBaseUrl").orElse("http://10.0.2.2:8000/api/v1").get()}\"")
            buildConfigField("Boolean", "IS_DEMO", "true")
        }
        create("pleos") {
            dimension = "runtime"
            buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("campApiBaseUrl").orElse("https://api.example.invalid/api/v1").get()}\"")
            buildConfigField("Boolean", "IS_DEMO", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    "pleosImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "pleosImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
}
