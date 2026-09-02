import nl.javadude.gradle.plugins.license.LicenseExtension
import java.util.*

plugins {
    `java-gradle-plugin`
    id("com.netflix.nebula.maven-apache-license")
    id("com.gradle.plugin-publish")
    id("com.github.hierynomus.license")
}

group = rootProject.group
description = "Settings-level build opinions for OpenRewrite repositories"

// Deliberately no org.openrewrite dependencies. This plugin is what makes them resolvable, so it has
// to come off the Gradle Plugin Portal on its own.
repositories {
    mavenCentral()
}

gradlePlugin {
    website = "https://github.com/openrewrite/rewrite-build-gradle-plugin"
    vcsUrl = "https://github.com/openrewrite/rewrite-build-gradle-plugin.git"
    plugins {
        create("build-settings") {
            id = "org.openrewrite.build.settings"
            displayName = "Rewrite settings"
            description = "Adds the Code Genome Project to pluginManagement.repositories, so that the " +
                    "org.openrewrite artifacts the build plugins are built on resolve."
            implementationClass = "org.openrewrite.gradle.RewriteSettingsPlugin"
            tags = listOf("rewrite", "refactoring", "settings")
        }
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(17)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.+"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:latest.release")
    testImplementation(gradleTestKit())
}

tasks.withType<Test> {
    useJUnitPlatform()
}

rootProject.tasks.named("postRelease").configure { dependsOn(tasks.named("publishPlugins")) }

configure<LicenseExtension> {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = rootProject.file("gradle/licenseHeader.txt")
    mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
    strictCheck = true
}
