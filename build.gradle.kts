buildscript {
    val patches = java.util.Properties().apply {
        file("gradle/security-versions.properties").inputStream().use { load(it) }
    }
    configurations.configureEach {
        resolutionStrategy.activateDependencyLocking()
        resolutionStrategy.eachDependency {
            val key = when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> "io.netty:4.1"
                requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on") -> "org.bouncycastle:jdk18on"
                else -> "${requested.group}:${requested.name}"
            }
            patches.getProperty(key)?.let { useVersion(it); because("Reviewed dependency security fixes") }
        }
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// GHSA-r937-wjx7-w2jp affects KAPT cache deserialization. This build uses KSP;
// activating KAPT requires a separately qualified toolchain containing the fix.
allprojects {
    listOf("org.jetbrains.kotlin.kapt", "kotlin-kapt").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            throw GradleException("KAPT is forbidden while GHSA-r937-wjx7-w2jp remains in the toolchain; use the reviewed KSP configuration")
        }
    }
}

// Freeze resolved transitive versions as well as the explicitly pinned direct dependencies.
subprojects {
    dependencyLocking { lockAllConfigurations() }
    val patches = java.util.Properties().apply {
        rootProject.file("gradle/security-versions.properties").inputStream().use { load(it) }
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val key = when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> "io.netty:4.1"
                requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on") -> "org.bouncycastle:jdk18on"
                else -> "${requested.group}:${requested.name}"
            }
            patches.getProperty(key)?.let { useVersion(it); because("Reviewed dependency security fixes") }
        }
    }
}
