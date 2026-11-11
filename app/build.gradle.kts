import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public version inputs only. Every Gradle release is unsigned; private signing
// happens separately through tools/sign_release.py, outside AGP intermediates.
val checkedInVersion = Properties().apply {
    rootProject.file("gradle/version.properties").inputStream().use { load(it) }
}
val checkedInVersionCode = checkedInVersion.getProperty("versionCode") ?: error("Public versionCode missing")
val checkedInVersionName = checkedInVersion.getProperty("versionName") ?: error("Public versionName missing")
require(checkedInVersionCode.toIntOrNull()?.let { it in 1..2100000000 } == true &&
    checkedInVersionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}"))) {
    "Checked-in public version fields must be valid"
}
val releaseVersionCode = providers.environmentVariable("CALENDEER_VERSION_CODE").orNull
val releaseVersionName = providers.environmentVariable("CALENDEER_VERSION_NAME").orNull
require((releaseVersionCode == null) == (releaseVersionName == null)) { "Supply both release version fields" }
require(releaseVersionCode == null || releaseVersionCode.toIntOrNull()?.let { it in 1..2100000000 } == true) {
    "CALENDEER_VERSION_CODE must be a positive Android version code"
}
require(releaseVersionName == null || releaseVersionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}"))) {
    "CALENDEER_VERSION_NAME must be a safe version name"
}

android {
    namespace = "com.patmanak.proton.andcalendarbridge"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.patmanak.proton.andcalendarbridge"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode?.toInt() ?: checkedInVersionCode.toInt()
        versionName = releaseVersionName ?: checkedInVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "fr", "de", "es", "it", "nl", "pl", "pt")
    }
    bundle { language { enableSplit = false } }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
// JSR305 is annotation-only in the inspected dependency sources. Its concurrent
// annotations have separate CC-BY-2.5 grants; keep them out of the shipped release.
// Keep compiler/tooling visibility and all other configurations unchanged.
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    exclude(group = "com.google.code.findbugs", module = "jsr305")
}
dependencies {
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    implementation(project(":sync"))
    implementation(project(":auth"))
    implementation(project(":proton-auth"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    // Host codec checks only; Android continues using its own org.json implementation.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Android 17 removed the hidden InputManager accessor used by Espresso 3.5.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-idling-resource:3.7.0")
    // Compile the release-runtime smoke against target Core classes, without
    // packaging another Core/crypto copy into the instrumentation APK.
    androidTestCompileOnly("me.proton.core:crypto-android:36.8.0")
    androidTestCompileOnly("me.proton.core:crypto-common:36.8.0")
    androidTestCompileOnly("me.proton.core:key-domain:36.8.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
