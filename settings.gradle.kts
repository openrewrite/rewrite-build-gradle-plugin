// Routes this build's own plugin classpath through the mirror CI names, or else CGP's Maven cache, which no project
// plugin can reach
pluginManagement {
    val ciMirrorUrl = providers.environmentVariable("MAVEN_CENTRAL_MIRROR_URL").getOrElse("")
    val ciMirrorUsername = providers.environmentVariable("MAVEN_CENTRAL_MIRROR_USERNAME").getOrElse("")
    val ciMirrorPassword = providers.environmentVariable("MAVEN_CENTRAL_MIRROR_PASSWORD").getOrElse("")
    val ciNamesMirror = ciMirrorUrl.isNotEmpty() && ciMirrorUsername.isNotEmpty() && ciMirrorPassword.isNotEmpty()
    // The portal's own repository beside maven-public, whose version lists are Central's for a plugin both list
    val mirrorUrl = (if (ciNamesMirror) ciMirrorUrl else "https://artifacts.codegenomeproject.org/maven-public/")
        .replace(Regex("/maven-public/?$"), "/gradle-plugins/")
    val mirrorUsername = if (ciNamesMirror) ciMirrorUsername else providers.gradleProperty("codegenomeUsername").getOrElse("")
    val mirrorPassword = if (ciNamesMirror) ciMirrorPassword else providers.gradleProperty("codegenomePassword").getOrElse("")
    repositories {
        gradlePluginPortal {
            if (mirrorUsername.isNotEmpty() && mirrorPassword.isNotEmpty()) {
                (this as MavenArtifactRepository).apply {
                    setUrl(mirrorUrl)
                    credentials {
                        username = mirrorUsername
                        password = mirrorPassword
                    }
                    mavenContent { releasesOnly() }
                }
            }
        }
    }
}

rootProject.name = "rewrite-build-gradle-plugin"
