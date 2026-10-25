plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
    id("org.openapi.generator")
}
fun telemetryProperty(property:String, environment:String, fallback:String=""):String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).orElse(fallback).get()
fun quoted(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
android {
    namespace = "camp.room.app"
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "camp.room.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String","SENTRY_DSN",quoted(telemetryProperty("campSentryDsn","CAMP_SENTRY_DSN")))
        buildConfigField("String","POSTHOG_API_KEY",quoted(telemetryProperty("campPosthogApiKey","CAMP_POSTHOG_API_KEY")))
        buildConfigField("String","POSTHOG_HOST",quoted(telemetryProperty("campPosthogHost","CAMP_POSTHOG_HOST","https://us.i.posthog.com")))
        buildConfigField("Boolean","TELEMETRY_CLOUD_ENABLED",(telemetryProperty("campTelemetryCloudEnabled","CAMP_TELEMETRY_CLOUD_ENABLED")=="true").toString())
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
    implementation(project(":api-client"))
    implementation("io.sentry:sentry-android-core:8.61.1")
    implementation("com.posthog:posthog-android:3.72.8")
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.dagger:hilt-android:2.56.2")
    kapt("com.google.dagger:hilt-compiler:2.56.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:3.1.3")
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    "pleosImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "pleosImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
}

// Pinned generator produces actual API/client/model sources from the committed server contract.
openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(file("../../api/openapi.json").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("camp.room.generated.api")
    modelPackage.set("camp.room.generated.model")
    invokerPackage.set("camp.room.generated.infrastructure")
    packageName.set("camp.room.generated")
    configOptions.set(mapOf(
        "serializationLibrary" to "kotlinx_serialization",
        "dateLibrary" to "string",
        "enumPropertyNaming" to "UPPERCASE",
        "hideGenerationTimestamp" to "true",
        "omitGradleWrapper" to "true",
        "sourceFolder" to "src/main/kotlin"
    ))
    // OpenAPI primitive unions are JSON scalars, never empty generated classes or Any.
    schemaMappings.set(mapOf(
        "Value" to "kotlinx.serialization.json.JsonPrimitive",
        "Observed" to "kotlinx.serialization.json.JsonPrimitive",
        "Target" to "kotlinx.serialization.json.JsonPrimitive",
        "LocInner" to "kotlinx.serialization.json.JsonPrimitive"
    ))
    globalProperties.set(mapOf("apiDocs" to "false", "modelDocs" to "false", "apiTests" to "false", "modelTests" to "false"))
}
kapt { correctErrorTypes = true }
tasks.named("preBuild") { dependsOn(tasks.named("openApiGenerate")) }
