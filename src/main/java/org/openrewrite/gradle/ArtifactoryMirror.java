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

import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenRepositoryContentDescriptor;
import org.gradle.api.provider.ProviderFactory;
import org.jspecify.annotations.Nullable;
import org.openrewrite.maven.tree.MavenRepository;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * Moderne's Artifactory cache of Maven Central and the Gradle Plugin Portal, which takes the place of both
 * wherever credentials for it are available. Maven Central rate limits by address, so a build that resolves
 * from it directly starts failing with HTTP 429 once enough builds behind the same address have done so.
 */
final class ArtifactoryMirror {

    static final String URL = "https://artifactory.moderne.ninja/artifactory/moderne-cache-3/";

    /**
     * The plugin portal belongs here because it answers for anything it does not host itself with a redirect
     * to Maven Central.
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
     * Credentials are the {@code artifactoryUsername} and {@code artifactoryPassword} Gradle properties, or
     * failing those the {@code REWRITE_GRADLE_MIRROR_*} environment variables. CI exports the latter, and so
     * does {@link #environment()} to the tests of a build that is itself mirrored, which is how a build those
     * tests go on to launch comes to be mirrored as well.
     *
     * @return {@code null} when neither is set, which leaves every repository where it was declared.
     */
    static @Nullable ArtifactoryMirror ifAvailable(ProviderFactory providers) {
        String username = providers.gradleProperty("artifactoryUsername").getOrElse("");
        String password = providers.gradleProperty("artifactoryPassword").getOrElse("");
        if (!username.isEmpty() && !password.isEmpty()) {
            return new ArtifactoryMirror(URL, username, password);
        }
        String url = providers.environmentVariable("REWRITE_GRADLE_MIRROR_URL").getOrElse("");
        username = providers.environmentVariable("REWRITE_GRADLE_MIRROR_USERNAME").getOrElse("");
        password = providers.environmentVariable("REWRITE_GRADLE_MIRROR_PASSWORD").getOrElse("");
        if (!url.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
            return new ArtifactoryMirror(url, username, password);
        }
        return null;
    }

    /**
     * Whatever these repositories would fetch from Maven Central, now or once more of them are declared, they
     * fetch from the mirror instead. A repository is repointed where it stands rather than replaced, which
     * keeps its place in the order and the content filters it was declared with.
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

    boolean serves(MavenArtifactRepository repo) {
        return URI.create(url).equals(repo.getUrl());
    }

    /**
     * For OpenRewrite's {@code MavenPomDownloader}, which adds Maven Central behind whatever repositories it
     * is given unless one of them already goes by Central's id.
     */
    MavenRepository asMavenCentral() {
        return MavenRepository.MAVEN_CENTRAL.withUri(url).withUsername(username).withPassword(password);
    }

    /**
     * The variables CI exports, which the tests of {@code rewrite-gradle} and {@code rewrite-maven} resolve
     * through, and which {@link #ifAvailable} reads back in any build those tests launch.
     */
    Map<String, String> environment() {
        return Map.of(
                "REWRITE_GRADLE_MIRROR_URL", url,
                "REWRITE_GRADLE_MIRROR_USERNAME", username,
                "REWRITE_GRADLE_MIRROR_PASSWORD", password);
    }
}
