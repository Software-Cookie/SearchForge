rootProject.name = "searchforge"

dependencyResolutionManagement {
    repositories {
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
