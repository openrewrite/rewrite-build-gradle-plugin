import nl.javadude.gradle.plugins.license.LicenseExtension
import java.util.*

plugins {
    id("nebula.release") version "17.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("org.owasp.dependencycheck") version "8.0.1"
    id("nebula.maven-resolved-dependencies") version "18.4.0"
    id("nebula.maven-apache-license") version "18.4.0"
    id("com.gradle.plugin-publish") version "1.1.0"
    id("com.github.hierynomus.license") version "0.16.1"
}

group = "org.openrewrite"
description = "Eliminate Tech-Debt. At build time."

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    analyzers.assemblyEnabled = false
    failBuildOnCVSS = 9.0F
    suppressionFile = "suppressions.xml"
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

pluginBundle {
    website = "https://github.com/openrewrite/rewrite-build-gradle-plugin"
    vcsUrl = "https://github.com/openrewrite/rewrite-build-gradle-plugin.git"
    tags = listOf("rewrite", "refactoring", "java")
}

gradlePlugin {
    plugins {
        create("build-language-library") {
            id = "org.openrewrite.build.language-library"
            displayName = "Rewrite language library"
            description = "Core language module"
            implementationClass = "org.openrewrite.gradle.RewriteLanguageLibraryPlugin"
        }
        create("build-recipe-library") {
            id = "org.openrewrite.build.recipe-library"
            displayName = "Rewrite recipe library"
            description = "A recipe library"
            implementationClass = "org.openrewrite.gradle.RewriteRecipeLibraryPlugin"
        }
        create("build-java-base") {
            id = "org.openrewrite.build.java-base"
            displayName = "Rewrite Java"
            description = "A module that is built with Java but does not publish artifacts"
            implementationClass = "org.openrewrite.gradle.RewriteJavaPlugin"
        }
        create("build-publish") {
            id = "org.openrewrite.build.publish"
            displayName = "Rewrite Maven publishing"
            description = "Configures publishing to Maven repositories"
            implementationClass = "org.openrewrite.gradle.RewritePublishPlugin"
        }
        create("build-shadow") {
            id = "org.openrewrite.build.shadow"
            displayName = "Rewrite shadow configuration"
            description = "Configures the Gradle Shadow plugin to replace the normal jar task output with " +
                    "the shaded jar without a classifier"
            implementationClass = "org.openrewrite.gradle.RewriteShadowPlugin"
        }
        create("build-metadata") {
            id = "org.openrewrite.build.metadata"
            displayName = "Rewrite metadata configuration"
            description = "Contacts and SCM data"
            implementationClass = "org.openrewrite.gradle.RewriteMetadataPlugin"
        }
        create("build-java8-text-blocks") {
            id = "org.openrewrite.build.java8-text-blocks"
            displayName = "Java 8 text blocks"
            description = "Text blocks compiled to Java 8 bytecode"
            implementationClass = "org.openrewrite.gradle.RewriteJava8TextBlocksPlugin"
        }
        create("build-root") {
            id = "org.openrewrite.build.root"
            displayName = "Rewrite root"
            description = "Configures the root project"
            implementationClass = "org.openrewrite.gradle.RewriteRootProjectPlugin"
        }
        create("build-recipe-author-attribution") {
            id = "org.openrewrite.build.recipe-author-attribution"
            displayName = "Rewrite recipe author attribution"
            description = "Produces a `/META-INF/rewrite/recipe-authors.yml` file containing recipe author attribution"
            implementationClass = "org.openrewrite.gradle.RewriteRecipeAuthorAttributionPlugin"
        }

        create("build-recipe-examples") {
            id = "org.openrewrite.build.recipe-examples"
            displayName = "Rewrite recipe examples"
            description = "Produces a `/META-INF/rewrite/recipe-example.yml` file containing recipe examples"
            implementationClass = "org.openrewrite.gradle.RewriteRecipeExamplesPlugin"
        }
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        if(name.startsWith("test")) {
            eachDependency {
                if(requested.name == "groovy-xml") {
                    useVersion("3.0.9")
                }
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
}

val rewriteVersion = "8.0.0"

dependencies {
    compileOnly("org.openrewrite.gradle.tooling:model:latest.release")

    implementation("org.openrewrite:rewrite-java:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-test:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-core:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-xml:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-properties:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-yaml:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-gradle:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-maven:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-java-8:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-java-11:${rewriteVersion}")
    implementation("org.openrewrite:rewrite-java-17:${rewriteVersion}")

    compileOnly("org.projectlombok:lombok:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")

    implementation("org.apache.ivy:ivy:2.5.1")
    implementation("gradle.plugin.com.hierynomus.gradle.plugins:license-gradle-plugin:latest.release")
    implementation("com.github.jk1:gradle-license-report:1.16")
    implementation("org.owasp:dependency-check-gradle:latest.release")
    implementation("com.netflix.nebula:gradle-contacts-plugin:latest.release")
    implementation("com.netflix.nebula:gradle-info-plugin:latest.release")
    implementation("com.netflix.nebula:nebula-release-plugin:latest.release")
    implementation("com.netflix.nebula:nebula-publishing-plugin:latest.release")
    implementation("com.netflix.nebula:nebula-project-plugin:latest.release")
    implementation("io.github.gradle-nexus:publish-plugin:latest.release")
    implementation("gradle.plugin.com.github.johnrengelman:shadow:7.1.2") // Pinned till we upgrade everything to Gradle 8.0
    implementation("org.gradle:test-retry-gradle-plugin:latest.release")

    implementation(platform("com.fasterxml.jackson:jackson-bom:2.15.+"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    implementation("org.yaml:snakeyaml:latest.release")
    implementation("io.github.classgraph:classgraph:latest.release")
    implementation("org.eclipse.jgit:org.eclipse.jgit:latest.release")

    testImplementation(platform("org.junit:junit-bom:latest.release"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.assertj:assertj-core:latest.release")

    testImplementation("org.openrewrite:rewrite-java:${rewriteVersion}")
    testImplementation("org.openrewrite:rewrite-test:${rewriteVersion}")

    testImplementation(gradleTestKit())
}

project.rootProject.tasks.getByName("postRelease").dependsOn(project.tasks.getByName("publishPlugins"))

tasks.withType<Test>() {
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
}
