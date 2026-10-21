buildscript {
    // Override AGP's built-in Kotlin/KSP baseline with the qualified security update.
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
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
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
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
