@file:Suppress("UnstableApiUsage")

import nl.javadude.gradle.plugins.license.LicenseExtension
import java.util.*

plugins {
    id("com.netflix.nebula.release") version "21.0.2"
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
        create("build-recipe-marketplace") {
            id = "org.openrewrite.build.recipe-marketplace"
            displayName = "Rewrite recipe marketplace"
            description = "Provides tasks for generating and validating recipes.csv for the recipe marketplace"
            implementationClass = "org.openrewrite.gradle.RewriteRecipeMarketplacePlugin"
            tags = listOf("rewrite", "refactoring")
        }
        create("build-recipe-repositories") {
            id = "org.openrewrite.build.recipe-repositories"
            displayName = "Rewrite recipe repositories"
            description =
                "Configures the repositories that OpenRewrite modules in open source draw dependencies from: " +
                        "the Code Genome Project for org.openrewrite and io.moderne artifacts, Maven Central for the rest. "
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
        create("build-publish-maven-central") {
            id = "org.openrewrite.build.publish-maven-central"
            displayName = "Rewrite Maven Central publishing"
            description = "Deprecated. Stages and releases artifacts to Maven Central through Sonatype. " +
                    "OpenRewrite publishes to the Code Genome Project instead; use org.openrewrite.build.publish-cgp."
            implementationClass = "org.openrewrite.gradle.RewriteMavenCentralPublishPlugin"
            tags = listOf("rewrite", "refactoring", "deprecated")
        }
        create("build-publish-cgp") {
            id = "org.openrewrite.build.publish-cgp"
            displayName = "Rewrite Code Genome Project publishing"
            description = "Publishes recipe artifacts to the Code Genome Project"
            implementationClass = "org.openrewrite.gradle.RewriteCgpPublishPlugin"
            tags = listOf("rewrite", "refactoring", "oss")
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
        create("build-bom-alignment") {
            id = "org.openrewrite.build.bom-alignment"
            displayName = "BOM dependency version alignment check"
            description = "Adds a checkBomAlignment task that fails the build if the BOM's transitive graph " +
                    "requests any org.openrewrite.* or io.moderne.* dependency at more than one version. " +
                    "Hooks into the check lifecycle and any AbstractPublishToMaven task."
            implementationClass = "org.openrewrite.gradle.RewriteBomAlignmentPlugin"
            tags = listOf("rewrite", "refactoring", "bom")
        }
    }
}

val codegenomeUsername = providers.gradleProperty("codegenomeUsername").getOrElse("")
val codegenomePassword = providers.gradleProperty("codegenomePassword").getOrElse("")
val codegenomeConfigured = codegenomeUsername.isNotEmpty() && codegenomePassword.isNotEmpty()
if (!codegenomeConfigured && hasProperty("releasing")) {
    throw GradleException(
        """
        Code Genome Project credentials are required to release this project, so that its org.openrewrite and
        io.moderne dependencies resolve from https://artifacts.codegenomeproject.org/maven rather than falling
        back to older versions on Maven Central.

        Set them in ~/.gradle/gradle.properties:

            codegenomeUsername=you@example.com
            codegenomePassword=cgp_...

        or expose them as the ORG_GRADLE_PROJECT_codegenomeUsername and ORG_GRADLE_PROJECT_codegenomePassword
        environment variables.
        """.trimIndent()
    )
}

repositories {
    mavenLocal()
    if (codegenomeConfigured) {
        maven {
            name = "codegenome"
            url = uri("https://artifacts.codegenomeproject.org/maven")
            credentials {
                username = codegenomeUsername
                password = codegenomePassword
            }
            content {
                includeGroupAndSubgroups("org.openrewrite")
                includeGroupAndSubgroups("io.moderne")
            }
        }
    }
    // The plugin portal proxies Maven Central, so it needs the same exclusion to keep these groups on CGP
    gradlePluginPortal {
        if (codegenomeConfigured) {
            (this as MavenArtifactRepository).content {
                excludeGroupAndSubgroups("org.openrewrite")
                excludeGroupAndSubgroups("io.moderne")
            }
        }
    }
    mavenCentral {
        if (codegenomeConfigured) {
            content {
                excludeGroupAndSubgroups("org.openrewrite")
                excludeGroupAndSubgroups("io.moderne")
            }
        }
    }
}

configurations.all {
    resolutionStrategy {
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

tasks.named<JavaCompile>("compileJava") {
    options.release.set(17)
}

val rewriteVersion = if (hasProperty("releasing")) "latest.release" else "latest.integration"

dependencies {
    implementation("org.openrewrite:rewrite-java:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-core:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-maven:${rewriteVersion}")

    compileOnly("org.projectlombok:lombok:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")

    implementation("org.apache.ivy:ivy:2.6.0")
    implementation("org.apache.maven:maven-plugin-api:3.9.14")
    implementation("gradle.plugin.com.hierynomus.gradle.plugins:license-gradle-plugin:latest.release")
    implementation("com.github.jk1:gradle-license-report:1.16")
    implementation("org.owasp:dependency-check-gradle:latest.release") {
        exclude(group = "org.apache.lucene", module = "lucene-facet") // 9.12.3 Brings in a vulnerability
    }
    implementation("com.netflix.nebula.contacts:com.netflix.nebula.contacts.gradle.plugin:latest.release")
    implementation("com.netflix.nebula.info:com.netflix.nebula.info.gradle.plugin:latest.release")
    implementation("com.netflix.nebula.release:com.netflix.nebula.release.gradle.plugin:21.0.2")
    implementation("com.netflix.nebula:nebula-publishing-plugin:latest.release")
    implementation("com.netflix.nebula:nebula-project-plugin:latest.release")
    implementation("io.github.gradle-nexus:publish-plugin:latest.release")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.0.0-beta7") // Latest supporting Java 8

    implementation("org.jspecify:jspecify:1.0.0")
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.6"))
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
        implementation("com.mycila:license-maven-plugin") {
            version { strictly("4.0") }
            because("license-gradle-plugin 0.16.1 resolves 3.0, whose Document needs Spring's " +
                    "PropertyPlaceholderHelper; 4.0 needs no Spring. 4.6 takes a Charset where 0.16.1 " +
                    "passes a String to Document's constructor, so this is an upper bound too.")
        }
        implementation("com.squareup.moshi:moshi:1.15.2") {
            because("CVE-2023-3635: gradle-dependency-lock-plugin still pins moshi 1.12.0, which brings okio 2.10.0")
        }
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
        implementation("org.codehaus.plexus:plexus-utils:4.0.3") {
            because("CVE-2022-4244, CVE-2022-4245")
        }
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.4") {
            because("CVE-2026-40542, CVE-2026-64607")
        }
        implementation("org.apache.httpcomponents.client5:httpclient5-cache:5.6.4") {
            because("CVE-2026-40542, CVE-2026-64607")
        }
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3") {
            because("CVE-2026-54399")
        }
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3") {
            because("CVE-2026-54399")
        }
        implementation("org.apache.logging.log4j:log4j-api:2.25.5") {
            because("CVE-2026-34477, CVE-2026-34479, CVE-2026-49844")
        }
        implementation("org.apache.logging.log4j:log4j-core:2.25.5") {
            because("CVE-2026-34478, CVE-2026-34479, CVE-2026-34480, CVE-2025-68161, CVE-2026-34477, CVE-2026-49844")
        }
    }
}

project.rootProject.tasks.getByName("postRelease").dependsOn(project.tasks.getByName("publishPlugins"))

tasks.withType<Test> {
    useJUnitPlatform()
    // RewriteJavaPluginTest asserts against this file, which Gradle would not otherwise treat as a test input
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("buildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
