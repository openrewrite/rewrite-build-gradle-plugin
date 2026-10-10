/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenRepositoryContentDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.net.URI;
import java.util.Set;

/**
 * Adds the Code Genome Project to {@code pluginManagement.repositories}, so that the
 * {@code org.openrewrite} artifacts the build plugins themselves are built on resolve.
 * <p>
 * {@code org.openrewrite.build.recipe-repositories} does this for a project's own dependencies, but a
 * plugin's classpath is resolved from the repositories settings declares, before any project exists.
 * Those default to the Gradle Plugin Portal, which proxies Maven Central, and since recipe and
 * language libraries stopped publishing there, {@code org.openrewrite:rewrite-core} and its siblings
 * are no longer on it — so applying any of these plugins fails before it can configure anything.
 * <p>
 * Where there are credentials for Moderne's Artifactory cache of Maven Central and the plugin portal, it
 * also routes through that cache whatever the build would otherwise fetch from either: the plugins'
 * classpaths, which only settings can reach, as well as every project's repositories and those of its
 * build script.
 * <p>
 * This plugin carries no {@code org.openrewrite} dependencies of its own, which is what lets it
 * resolve from the plugin portal alone and then make everything else resolvable.
 */
public class RewriteSettingsPlugin implements Plugin<Settings> {

    static final String ID = "org.openrewrite.build.settings";
    static final String CGP_ID = "codegenome";
    static final String CGP_URL = "https://artifacts.codegenomeproject.org/maven";

    private static final Logger logger = Logging.getLogger(RewriteSettingsPlugin.class);

    @Override
    public void apply(Settings settings) {
        addCodegenome(settings);

        ArtifactoryMirror mirror = ArtifactoryMirror.ifAvailable(settings);
        if (mirror != null) {
            RepositoryHandler pluginRepositories = settings.getPluginManagement().getRepositories();
            // The plugin portal an empty handler falls back to has to be declared before it can be redirected
            if (pluginRepositories.isEmpty()) {
                pluginRepositories.gradlePluginPortal();
            }
            mirror.redirect(pluginRepositories);
            mirror.redirect(settings.getDependencyResolutionManagement().getRepositories());
            settings.getGradle().beforeProject(project -> {
                mirror.redirect(project.getBuildscript().getRepositories());
                mirror.redirect(project.getRepositories());
            });
        }
    }

    private static void addCodegenome(Settings settings) {
        String username = gradleProperty(settings, "codegenomeUsername");
        String password = gradleProperty(settings, "codegenomePassword");
        if (username.isEmpty() || password.isEmpty()) {
            // Fork pull requests cannot see repository secrets. Nothing here can make their builds
            // resolve, but failing outright would only replace one unhelpful error with another.
            logger.warn("Code Genome Project credentials are absent, so " + CGP_URL + " is not among the\n" +
                        "repositories plugins resolve from. An org.openrewrite build plugin will fail to resolve its\n" +
                        "own dependencies. Set codegenomeUsername and codegenomePassword in ~/.gradle/gradle.properties,\n" +
                        "or expose them as ORG_GRADLE_PROJECT_codegenomeUsername and ORG_GRADLE_PROJECT_codegenomePassword.");
            return;
        }

        RepositoryHandler repositories = settings.getPluginManagement().getRepositories();
        // An empty handler is what makes Gradle fall back to the plugin portal, so adding to one opts out of it
        if (repositories.isEmpty()) {
            repositories.gradlePluginPortal();
        }
        if (repositories.findByName(CGP_ID) != null) {
            return;
        }
        // Appended rather than prepended, which a RepositoryHandler cannot do. Nothing is excluded from
        // the repositories already there: a plugin's classpath is pinned to exact versions, so a hit
        // anywhere is the same artifact, and excluding org.openrewrite from the portal would take the
        // plugin markers with it.
        repositories.maven(repo -> {
            repo.setName(CGP_ID);
            repo.setUrl(CGP_URL);
            repo.credentials(credentials -> {
                credentials.setUsername(username);
                credentials.setPassword(password);
            });
            repo.content(content -> {
                content.includeGroupAndSubgroups("org.openrewrite");
                content.includeGroupAndSubgroups("io.moderne");
            });
        });
    }

    private static String gradleProperty(Settings settings, String name) {
        return settings.getProviders().gradleProperty(name).getOrElse("");
    }

    private static String environmentVariable(Settings settings, String name) {
        return settings.getProviders().environmentVariable(name).getOrElse("");
    }

    /**
     * The settings-level counterpart of the class by this name next to {@code RewriteArtifactoryMirrorPlugin},
     * which does the same for a single project's repositories. The two cannot share it: this plugin has to
     * resolve before that one's artifact can.
     */
    private static final class ArtifactoryMirror {

        private static final String URL = "https://artifactory.moderne.ninja/artifactory/moderne-cache-3/";

        /**
         * The plugin portal belongs here because it answers for anything it does not host itself with a
         * redirect to Maven Central, which rate limits by address.
         */
        private static final Set<String> MAVEN_CENTRAL_HOSTS = Set.of(
                "repo.maven.apache.org",
                "repo1.maven.org",
                "plugins.gradle.org");

        private final String url;
        private final String username;
        private final String password;

        private ArtifactoryMirror(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
        }

        /**
         * Credentials are the {@code artifactoryUsername} and {@code artifactoryPassword} Gradle properties,
         * or failing those the {@code REWRITE_GRADLE_MIRROR_*} environment variables, which CI exports and a
         * mirrored build hands to the builds its tests launch.
         *
         * @return {@code null} when neither is set, which leaves every repository where it was declared.
         */
        static ArtifactoryMirror ifAvailable(Settings settings) {
            String username = gradleProperty(settings, "artifactoryUsername");
            String password = gradleProperty(settings, "artifactoryPassword");
            if (!username.isEmpty() && !password.isEmpty()) {
                return new ArtifactoryMirror(URL, username, password);
            }
            String url = environmentVariable(settings, "REWRITE_GRADLE_MIRROR_URL");
            username = environmentVariable(settings, "REWRITE_GRADLE_MIRROR_USERNAME");
            password = environmentVariable(settings, "REWRITE_GRADLE_MIRROR_PASSWORD");
            if (!url.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                return new ArtifactoryMirror(url, username, password);
            }
            return null;
        }

        /**
         * A repository is repointed where it stands rather than replaced, which keeps its place in the order
         * and the content filters it was declared with. That goes for repositories declared later too.
         */
        void redirect(RepositoryHandler repositories) {
            repositories.withType(MavenArtifactRepository.class).configureEach(repo -> {
                if (!reachesMavenCentral(repo)) {
                    return;
                }
                repo.setUrl(url);
                repo.credentials(credentials -> {
                    credentials.setUsername(username);
                    credentials.setPassword(password);
                });
                // The mirror also carries snapshots, which neither repository it stands in for ever does. Gradle
                // knows that of mavenCentral() already, but not of the plugin portal or of a Central URL spelled out.
                repo.mavenContent(MavenRepositoryContentDescriptor::releasesOnly);
            });
        }

        private static boolean reachesMavenCentral(MavenArtifactRepository repo) {
            // Neither is a given: a repository can be added before it is given a URL, and a file URL has no host
            URI declared = repo.getUrl();
            String host = declared == null ? null : declared.getHost();
            return host != null && MAVEN_CENTRAL_HOSTS.contains(host);
        }
    }
}
