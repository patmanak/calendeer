import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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

val sourceBuiltCryptoGroup = "com.patmanak.calendeer.crypto"
val sourceBuiltCryptoVersion = "2.10.0-2-go1.27.1-16k-linux1"
extra["calendeerNativeVersion"] = sourceBuiltCryptoVersion
val nativeSource = layout.projectDirectory.dir("proton-auth/native/golib")
val nativeRepository = layout.projectDirectory.dir("proton-auth/native/build/maven")
val nativeArtifact = nativeRepository.file(
    "com/patmanak/calendeer/crypto/android-golib/$sourceBuiltCryptoVersion/android-golib-$sourceBuiltCryptoVersion.aar")
val nativePom = nativeArtifact.asFile.resolveSibling("android-golib-$sourceBuiltCryptoVersion.pom")
val nativeDescriptors = listOf("go.mod", "go.sum", "dependencies.go", "build.sh", "build.ps1", "package-aar.py", "golib.pom")

// Gradle trusts local Maven files. Enforce reviewed bytes and the complete source
// descriptor binding explicitly, before any Android assembly can use the artifact.
val verifySourceBuiltCrypto = tasks.register("verifySourceBuiltCrypto") {
    group = "verification"
    description = "Reject missing, changed or stale source-built native cryptography."
    inputs.files(nativeArtifact, nativePom, layout.projectDirectory.file("gradle/verification-metadata.xml"))
    inputs.files(nativeDescriptors.map { nativeSource.file(it) })
    doLast {
        val artifact = nativeArtifact.asFile
        check(artifact.isFile && nativePom.isFile) {
            "Source-built native cryptography missing; build proton-auth/native/golib first"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val components = factory.newDocumentBuilder().parse(file("gradle/verification-metadata.xml"))
            .getElementsByTagName("component")
        val component = (0 until components.length).map { components.item(it) as Element }.single {
            it.getAttribute("group") == sourceBuiltCryptoGroup && it.getAttribute("name") == "android-golib" &&
                it.getAttribute("version") == sourceBuiltCryptoVersion
        }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        for (candidate in listOf(artifact, nativePom)) {
            val entries = component.getElementsByTagName("artifact")
            val hashes = (0 until entries.length).map { entries.item(it) as Element }
                .single { it.getAttribute("name") == candidate.name }.getElementsByTagName("sha256")
            check(hashes.length == 1 && digest(candidate.readBytes()) == (hashes.item(0) as Element).getAttribute("value")) {
                "Source-built native cryptography checksum mismatch"
            }
        }
        ZipFile(artifact).use { archive ->
            val provenance = archive.getEntry("META-INF/calendeer-native-provenance.json")
                ?: error("Source-built native provenance missing")
            val record = archive.getInputStream(provenance).use { JsonSlurper().parse(it) } as Map<*, *>
            check(record["coordinate"] == "$sourceBuiltCryptoGroup:android-golib:$sourceBuiltCryptoVersion")
            check(record["upstream_reference_sha256"] == "f64705f4ae14dde5c619d03a3a7fc2e8d747fb2803c4b5da01b0c38dac7884c9")
            val descriptors = record["source_descriptors"] as Map<*, *>
            check(descriptors.keys == nativeDescriptors.toSet())
            for (name in nativeDescriptors) {
                val bytes = nativeSource.file(name).asFile.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                check(descriptors[name] == digest(bytes)) { "Native source inputs changed; rebuild and review" }
            }
            val native = record["native"] as List<*>
            val expected = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            check(native.size == 4)
            val paths = native.map { it as Map<*, *> }.map { row ->
                val name = row["entry"] as String
                check(row["go"] == "1.27.1" && row["relro_16k_compatible"] == true &&
                    (row["minimum_load_alignment"] as Number).toLong() >= 16384)
                val entry = archive.getEntry(name) ?: error("Native architecture missing")
                check(archive.getInputStream(entry).use { digest(it.readBytes()) } == row["sha256"])
                name
            }.toSet()
            check(paths == expected.map { "jni/$it/libgojni.so" }.toSet())
            check(archive.entries().asSequence().filter { it.name.startsWith("jni/") && it.name.endsWith(".so") }
                .map { it.name }.toSet() == paths)
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
            if (requested.group == "me.proton.crypto" && requested.name == "android-golib") {
                useTarget("$sourceBuiltCryptoGroup:android-golib:$sourceBuiltCryptoVersion")
                because("Use the checksum-guarded Calendeer source build; upstream JNI is reference-only")
            }
            val key = when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> "io.netty:4.1"
                requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on") -> "org.bouncycastle:jdk18on"
                else -> "${requested.group}:${requested.name}"
            }
            patches.getProperty(key)?.let { useVersion(it); because("Reviewed dependency security fixes") }
        }
    }
    plugins.withId("com.android.application") { tasks.named("preBuild").configure { dependsOn(verifySourceBuiltCrypto) } }
    plugins.withId("com.android.library") { tasks.named("preBuild").configure { dependsOn(verifySourceBuiltCrypto) } }
}
