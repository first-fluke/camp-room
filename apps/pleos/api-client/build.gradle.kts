plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin {
    jvmToolchain(17)
    compilerOptions { optIn.add("kotlin.io.encoding.ExperimentalEncodingApi") }
    sourceSets.getByName("main").kotlin.srcDir("../app/build/generated/openapi/src/main/kotlin")
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    testImplementation("io.ktor:ktor-client-mock:3.1.3")
    testImplementation("junit:junit:4.13.2")
}
tasks.named("compileKotlin") { dependsOn(":app:openApiGenerate") }
