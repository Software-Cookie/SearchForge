// Google's Maven Central mirror goes first: Render's shared build IPs get 429 from
// repo.maven.apache.org. Anything missing there falls through to the next repository.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

rootProject.name = "searchforge"

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

include(
    "sf-core",
    "sf-crawler",
    "sf-indexer",
    "sf-query",
    "sf-api",
    "sf-bench"
)
