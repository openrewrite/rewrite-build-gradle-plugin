// Routes this build's own plugin classpath through the Artifactory cache, which no project plugin can reach
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
