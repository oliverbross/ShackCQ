import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val requestedAbi = providers.gradleProperty("shackcqAbi").orNull?.trim()?.takeIf(String::isNotEmpty)
val requestedVersionCode = providers.gradleProperty("shackcqVersionCode").orNull?.toIntOrNull()
val requestedVersionName = providers.gradleProperty("shackcqVersionName").orNull?.trim()?.takeIf(String::isNotEmpty)
require(requestedAbi == null || requestedAbi in supportedAbis) {
    "shackcqAbi must be one of ${supportedAbis.joinToString()}"
}

android {
    namespace = "app.shackcq.mobile"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.shackcq.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = requestedVersionCode ?: 39
        versionName = requestedVersionName ?: "0.1.0-rc.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += "-std=c++17 -Wall -Wextra -Wpedantic" } }
        if (requestedAbi != null) ndk { abiFilters += requestedAbi }
    }

    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("test").resources.srcDir("../../fixtures")
}

val flexProperties = Properties()
rootProject.file("../flex-developer.properties").takeIf { it.isFile }?.inputStream()?.use { flexProperties.load(it) }
fun flexValue(name: String): String = providers.environmentVariable(name).orNull
    ?: flexProperties.getProperty(name).orEmpty()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val buildSha = providers.environmentVariable("GITHUB_SHA").orNull?.take(12) ?: runCatching {
    providers.exec {
        workingDir(rootProject.projectDir.parentFile)
        commandLine("git", "rev-parse", "--short=12", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("UNKNOWN")
val buildChannel = providers.environmentVariable("SHACKCQ_BUILD_CHANNEL").orNull?.trim().orEmpty().ifBlank { "development" }

android.defaultConfig {
    buildConfigField("String", "BUILD_SHA", quoted(buildSha))
    buildConfigField("String", "BUILD_CHANNEL", quoted(buildChannel))
    buildConfigField("String", "FLEX_SMARTLINK_CLIENT_ID", quoted(flexValue("FLEX_SMARTLINK_CLIENT_ID")))
    buildConfigField("String", "FLEX_SMARTLINK_AUTH_DOMAIN", quoted(flexValue("FLEX_SMARTLINK_AUTH_DOMAIN")))
    buildConfigField("String", "FLEX_SMARTLINK_REDIRECT_URI", quoted(flexValue("FLEX_SMARTLINK_REDIRECT_URI")))
    buildConfigField("String", "FLEX_SMARTLINK_SERVER", quoted(flexValue("FLEX_SMARTLINK_SERVER")))
}

val buildRustFlex by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Nexus-derived Flex core for every Android ABI"
    workingDir(rootProject.file("../rust/shackcq-flex"))
    val sdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT").orElse(providers.environmentVariable("ANDROID_HOME")).orNull
    if (sdkRoot != null) environment("ANDROID_NDK_HOME", file("$sdkRoot/ndk/${android.ndkVersion}").absolutePath)
    val targets = requestedAbi?.let(::listOf) ?: supportedAbis
    commandLine(listOf(providers.environmentVariable("CARGO").orElse("cargo").get(), "ndk") +
        targets.flatMap { listOf("-t", it) } + listOf("build", "--release"))
}

val hamlibSource = rootProject.file("../core/third_party/hamlib")
val hamlibBuildScript = file("src/main/cpp/hamlib/build_android.sh")
val hamlibOutput = layout.buildDirectory.dir("hamlib")
val buildHamlibAndroid by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the pinned Hamlib radio library for every Android ABI"
    val sdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
        .orElse(providers.environmentVariable("ANDROID_HOME"))
    inputs.dir(hamlibSource)
    inputs.file(hamlibBuildScript)
    val targets = requestedAbi?.let(::listOf) ?: supportedAbis
    outputs.files(targets.map {
        hamlibOutput.map { root -> root.file("$it/libhamlib.a") }
    })
    if (requestedAbi != null) environment("HAMLIB_ABIS", requestedAbi)
    commandLine("bash", hamlibBuildScript.absolutePath, hamlibSource.absolutePath,
        hamlibOutput.get().asFile.absolutePath,
        file("${sdkRoot.get()}/ndk/${android.ndkVersion}").absolutePath)
}

tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    dependsOn(buildRustFlex, buildHamlibAndroid)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation("org.maplibre.gl:android-sdk:13.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
