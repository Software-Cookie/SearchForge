plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.jmh) apply false
}

allprojects {
    group = "dev.searchforge"
    version = "1.0.0"
}

val catalog = libs

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf("-Xlint:all,-processing,-serial", "-parameters")
        )
    }

    dependencies {
        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.assertj)
        "testImplementation"(catalog.jqwik)
        "testRuntimeOnly"(catalog.junit.launcher)
        "implementation"(catalog.slf4j.api)
        "implementation"(catalog.logback.classic)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik")
        }
        maxHeapSize = "1g"
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        finalizedBy("jacocoTestReport")
    }

    configure<CheckstyleExtension> {
        toolVersion = catalog.versions.checkstyle.get()
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
        isIgnoreFailures = true // Non-blocking checkstyle for rapid iterations
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

project(":sf-crawler") {
    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(catalog.jsoup)
        "testImplementation"(catalog.wiremock)
    }
}

project(":sf-indexer") {
    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(catalog.jsoup)
    }
}

project(":sf-query") {
    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(project(":sf-indexer"))
    }
}

project(":sf-api") {
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(project(":sf-query"))
        "implementation"(project(":sf-indexer"))
        "implementation"(project(":sf-crawler"))
        "implementation"("org.springframework.boot:spring-boot-starter-web")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"(catalog.micrometer.core)
        "implementation"(catalog.micrometer.prometheus)
    }
}

project(":sf-bench") {
    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(project(":sf-query"))
        "implementation"(project(":sf-indexer"))
    }
}
