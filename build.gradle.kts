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
        toolchain.languageVersion.set(JavaLanguageVersion.of(catalog.versions.java.get().toInt()))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf("-Xlint:all,-processing,-serial", "-parameters")
        )
    }

    // Spring Boot manages logging and JUnit (6.x) for sf-api itself. The engine modules use
    // JUnit 5.14 because jqwik (property tests) runs on the JUnit 5 platform. Libraries only
    // depend on the SLF4J API; a logging backend is added where a program actually runs.
    val isBootApp = project.name == "sf-api"
    dependencies {
        "implementation"(catalog.slf4j.api)
        "testImplementation"(catalog.assertj)
        if (!isBootApp) {
            "testImplementation"(platform(catalog.junit.bom))
            "testImplementation"(catalog.junit.jupiter)
            "testImplementation"(catalog.jqwik)
            "testRuntimeOnly"(catalog.junit.launcher)
            "testRuntimeOnly"(catalog.logback.classic)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            if (isBootApp) includeEngines("junit-jupiter") else includeEngines("junit-jupiter", "jqwik")
        }
        maxHeapSize = "1g"
        systemProperty("jqwik.database", layout.buildDirectory.file("jqwik-database").get().asFile.path)
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
        isIgnoreFailures = false // Violations fail the build; drop noisy rules from checkstyle.xml instead
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    if (project.name != "sf-bench") { // benchmarks have no tests
        tasks.withType<JacocoCoverageVerification>().configureEach {
            dependsOn(tasks.withType<Test>())
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
    }
}

// Command-line tools run from the repo root, so paths like data/crawl work as written.
fun Project.commandLine(main: String) {
    apply(plugin = "application")
    configure<JavaApplication> { mainClass.set(main) }
    dependencies { "runtimeOnly"(catalog.logback.classic) }
    tasks.named<JavaExec>("run") {
        workingDir = rootProject.projectDir
        maxHeapSize = "2g"
    }
}

project(":sf-crawler") {
    commandLine("dev.searchforge.crawler.CrawlerMain")
    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(catalog.jsoup)
    }
}

project(":sf-indexer") {
    commandLine("dev.searchforge.indexer.IndexerMain")
    dependencies {
        "implementation"(project(":sf-core"))
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
        // web starter was renamed in Boot 4
        "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("com.github.ben-manes.caffeine:caffeine")
        "implementation"(catalog.micrometer.core)
        "implementation"(catalog.micrometer.prometheus)
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
        archiveFileName.set("sf-api.jar")
    }

    // The built React app (ui/dist, from `npm run build`) is served by Spring from /static.
    // Same origin for UI and API: no CORS configuration exists at all.
    tasks.named<ProcessResources>("processResources") {
        from(rootProject.file("ui/dist")) { into("static") }
    }

    // Run from the repo root so the default index path data/index/demo resolves.
    tasks.named<JavaExec>("bootRun") {
        workingDir = rootProject.projectDir
    }
}

project(":sf-bench") {
    apply(plugin = "me.champeau.jmh")
    commandLine("dev.searchforge.bench.RelevanceEval")
    dependencies {
        "implementation"(project(":sf-core"))
        "implementation"(project(":sf-query"))
        "implementation"(project(":sf-indexer"))
    }
    extensions.configure<me.champeau.jmh.JmhParameters>("jmh") {
        jmhVersion.set(catalog.versions.jmh.get())
        resultFormat.set("JSON")
        warmupIterations.set(2)
        iterations.set(3)
        fork.set(1)
        val indexDir = (findProperty("searchforge.index") ?: "data/index/demo").toString()
        jvmArgsAppend.set(listOf("-Dsearchforge.index=" + rootProject.file(indexDir).absolutePath))
    }
}
