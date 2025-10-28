@file:Suppress("UnstableApiUsage")

import nl.javadude.gradle.plugins.license.LicenseExtension
import java.util.*

plugins {
    id("com.netflix.nebula.release") version "latest.release"
    id("io.github.gradle-nexus.publish-plugin") version "latest.release"
    id("org.owasp.dependencycheck") version "latest.release"
    id("com.netflix.nebula.maven-resolved-dependencies") version "latest.release"
    id("com.netflix.nebula.maven-apache-license") version "latest.release"
    id("com.gradle.plugin-publish") version "latest.release"
    id("com.github.hierynomus.license") version "0.16.1"
}

group = "org.openrewrite"
description = "Eliminate Tech-Debt. At build time."

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    analyzers.assemblyEnabled = false
    analyzers.nodeAudit { enabled = false }
    analyzers.nodePackage { enabled = false }
    failBuildOnCVSS = System.getenv("FAIL_BUILD_ON_CVSS")?.toFloatOrNull() ?: 9.0F
    format = System.getenv("DEPENDENCY_CHECK_FORMAT") ?: "HTML"
    nvd.apiKey = System.getenv("NVD_API_KEY")
    analyzers.centralEnabled = System.getenv("CENTRAL_ANALYZER_ENABLED").toBoolean()
    analyzers.ossIndex.username = System.getenv("OSSINDEX_USERNAME")
    analyzers.ossIndex.password = System.getenv("OSSINDEX_PASSWORD")
    suppressionFile = "suppressions.xml"
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

gradlePlugin {
    website = "https://github.com/openrewrite/rewrite-build-gradle-plugin"
    vcsUrl = "https://github.com/openrewrite/rewrite-build-gradle-plugin.git"
    plugins {
        create("build-language-library") {
            id = "org.openrewrite.build.language-library"
            displayName = "Rewrite language library"
            description = "Core language module"
            implementationClass = "org.openrewrite.gradle.RewriteLanguageLibraryPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-recipe-library-base") {
            id = "org.openrewrite.build.recipe-library-base"
            displayName = "Rewrite recipe library base"
            description =
                "Builds recipe libraries with the minimum of opinions or conventions about any other aspect of the build. " +
                        "Does not configure artifact repositories or publishing. " +
                        "Use org.openrewrite.build.recipe-library if you want to build a recipe library as OSS."
            implementationClass = "org.openrewrite.gradle.RewriteRecipeLibraryBasePlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-recipe-repositories") {
            id = "org.openrewrite.build.recipe-repositories"
            displayName = "Rewrite recipe repositories"
            description =
                "Configures the repositories that OpenRewrite modules in open source draw dependencies from, " +
                        "such as Maven Central and Nexus Snapshots. "
            implementationClass = "org.openrewrite.gradle.RewriteDependencyRepositoriesPlugin"
            tags = listOf("rewrite", "refactoring", "oss")
        }
        create("build-recipe-library") {
            id = "org.openrewrite.build.recipe-library"
            displayName = "Rewrite recipe library"
            description =
                "Builds recipe libraries with all the conventions and configuration used in OpenRewrite repositories. " +
                        "Includes conventions around which repositories to draw from and publish to. " +
                        "Use org.openrewrite.build.recipe-library-base if you want to build a private recipe library."
            implementationClass = "org.openrewrite.gradle.RewriteRecipeLibraryPlugin"
            tags = listOf("rewrite", "refactoring", "oss")
        }
        create("build-java-base") {
            id = "org.openrewrite.build.java-base"
            displayName = "Rewrite Java"
            description = "A module that is built with Java but does not publish artifacts"
            implementationClass = "org.openrewrite.gradle.RewriteJavaPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-publish") {
            id = "org.openrewrite.build.publish"
            displayName = "Rewrite Maven publishing"
            description = "Configures publishing to Maven repositories"
            implementationClass = "org.openrewrite.gradle.RewritePublishPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-shadow") {
            id = "org.openrewrite.build.shadow"
            displayName = "Rewrite shadow configuration"
            description = "Configures the Gradle Shadow plugin to replace the normal jar task output with " +
                    "the shaded jar without a classifier"
            implementationClass = "org.openrewrite.gradle.RewriteShadowPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-metadata") {
            id = "org.openrewrite.build.metadata"
            displayName = "Rewrite metadata configuration"
            description = "Contacts and SCM data"
            implementationClass = "org.openrewrite.gradle.RewriteMetadataPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-java8-text-blocks") {
            id = "org.openrewrite.build.java8-text-blocks"
            displayName = "Java 8 text blocks"
            description = "Text blocks compiled to Java 8 bytecode"
            implementationClass = "org.openrewrite.gradle.RewriteJava8TextBlocksPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-root") {
            id = "org.openrewrite.build.root"
            displayName = "Rewrite root"
            description = "Configures the root project"
            implementationClass = "org.openrewrite.gradle.RewriteRootProjectPlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("moderne-source-available-license") {
            id = "org.openrewrite.build.moderne-source-available-license"
            displayName = "Moderne Source Available License"
            description = "Applies the MSAL to the project"
            implementationClass = "org.openrewrite.gradle.ModerneSourceAvailableLicensePlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("moderne-proprietary-license") {
            id = "org.openrewrite.build.moderne-proprietary-license"
            displayName = "Moderne Proprietary License"
            description = "Applies the Moderne Proprietary License to the project"
            implementationClass = "org.openrewrite.gradle.ModerneProprietaryLicensePlugin"
            tags = listOf("rewrite", "refactoring")
        }
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group == "org.apache.commons" &&
                requested.name == "commons-compress" &&
                requested.version.toString().startsWith("1.25")
            ) {
                useVersion("1.26.0")
            }
        }
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        if (name.startsWith("test")) {
            eachDependency {
                if (requested.name == "groovy-xml") {
                    useVersion("3.0.9")
                }
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(21)
}

val rewriteVersion = "latest.release"

dependencies {
    implementation("org.openrewrite:rewrite-java:${rewriteVersion}")

    compileOnly("org.projectlombok:lombok:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")

    implementation("org.apache.ivy:ivy:2.5.2")
    implementation("org.apache.maven:maven-plugin-api:3.9.6")
    implementation("gradle.plugin.com.hierynomus.gradle.plugins:license-gradle-plugin:latest.release") {
        exclude(group = "org.springframework", module = "spring-core")
        exclude(group = "org.springframework", module = "spring-asm")
    }
    // Provide a newer Spring version to replace the excluded 3.1.3
    implementation("org.springframework:spring-core:6.2.11")
    implementation("com.github.jk1:gradle-license-report:1.16")
    implementation("org.owasp:dependency-check-gradle:latest.release")
    implementation("com.netflix.nebula.contacts:com.netflix.nebula.contacts.gradle.plugin:latest.release")
    implementation("com.netflix.nebula:gradle-info-plugin:latest.release")
    implementation("com.netflix.nebula.release:com.netflix.nebula.release.gradle.plugin:latest.release")
    implementation("com.netflix.nebula:nebula-publishing-plugin:latest.release")
    implementation("com.netflix.nebula:nebula-project-plugin:latest.release")
    implementation("io.github.gradle-nexus:publish-plugin:latest.release")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.0.0-beta7") // Latest supporting Java 8

    implementation("org.jspecify:jspecify:1.0.0")
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.2"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform("org.junit:junit-bom:5.+"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:latest.release")

    testImplementation(gradleTestKit())

    constraints {
        implementation("org.apache.maven:maven-settings:3.9.6") {
            because("CVE-2021-26291")
        }
        implementation("com.h2database:h2:2.2.224") {
            because("CVE-2022-45868")
        }
        implementation("org.jdom:jdom2:2.0.6.1") {
            because("CVE-2021-33813")
        }
        implementation("org.codehaus.plexus:plexus-xml:4.0.3") {
            because("CVE-2022-4244, CVE-2022-4245")
        }
    }
}

project.rootProject.tasks.getByName("postRelease").dependsOn(project.tasks.getByName("publishPlugins"))


tasks.withType<Test> {
    useJUnitPlatform()
}

configure<LicenseExtension> {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = project.rootProject.file("gradle/licenseHeader.txt")
    mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
    strictCheck = true
    exclude("**/versions.properties")
    exclude("**/*.txt")
    exclude("**/suppressions.xml")
}
