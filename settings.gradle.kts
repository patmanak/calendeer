pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "CalendeerSourceBuiltCrypto"
                    url = uri("$rootDir/proton-auth/native/build/maven")
                    metadataSources { mavenPom(); artifact() }
                }
            }
            filter { includeGroup("com.patmanak.calendeer.crypto") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "CalendarBridge"
include(":sync", ":auth", ":app", ":proton-auth")
