import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.patmanak.proton.andcalendarbridge.protonauth"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions.jvmTarget = JvmTarget.JVM_17 }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "me.proton.core" && requested.version != "36.8.0") {
            throw GradleException("Mixed Proton Core release trains are forbidden")
        }
    }
}
dependencies {
    implementation(project(":auth"))
    implementation(project(":sync"))
    implementation("net.sf.biweekly:biweekly:0.6.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    constraints {
        implementation("me.proton.crypto:android-golib:2.10.0-2") {
            version { strictly("2.10.0-2") }
        }
    }
    listOf(
        "account-domain", "account-data", "auth-domain", "auth-data", "auth-fido-domain",
        "challenge-domain", "challenge-data", "network-domain", "network-data",
        "user-domain", "user-data", "key-domain", "key-data", "crypto-android", "data-room",
    ).forEach { implementation("me.proton.core:$it:36.8.0") }
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
