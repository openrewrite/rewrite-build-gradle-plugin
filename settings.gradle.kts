// Mirrors RewriteSettingsPlugin, which this build cannot apply to itself
pluginManagement {
    val artifactoryUsername = providers.gradleProperty("artifactoryUsername").getOrElse("")
    val artifactoryPassword = providers.gradleProperty("artifactoryPassword").getOrElse("")
    repositories {
        gradlePluginPortal {
            if (artifactoryUsername.isNotEmpty() && artifactoryPassword.isNotEmpty()) {
                (this as MavenArtifactRepository).apply {
                    setUrl("https://artifactory.moderne.ninja/artifactory/moderne-cache-3/")
                    credentials {
                        username = artifactoryUsername
                        password = artifactoryPassword
                    }
                    mavenContent { releasesOnly() }
                }
            }
        }
    }
}

rootProject.name = "rewrite-build-gradle-plugin"

include("rewrite-build-settings-plugin")

plugins {
    id("com.gradle.develocity") version "latest.release"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "latest.release"
}

develocity {
    server = "https://community.develocity.cloud/"
    projectId = "openrewrite"
    val isCiServer = System.getenv("CI")?.equals("true") ?: false
    val accessKey = System.getenv("GRADLE_ENTERPRISE_ACCESS_KEY")
    val authenticated = !accessKey.isNullOrBlank()
    buildCache {
        remote(develocity.buildCache) {
            isEnabled = true
            isPush = isCiServer && authenticated
        }
    }

    buildScan {
        capture {
            fileFingerprints = true
        }

        publishing {
            onlyIf {
                authenticated
            }
        }

        uploadInBackground = !isCiServer
    }
}
