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
 * What takes the place of Maven Central and the Gradle Plugin Portal wherever a build has credentials for it: the
 * mirror CI names, or else the Code Genome Project's Maven cache. Maven Central rate limits by address, so a build
 * that resolves from it directly starts failing with HTTP 429 once enough builds behind the same address have done so.
 */
final class MavenCentralMirror {

    /**
     * The Code Genome Project's pull-through cache of Maven Central, the Gradle Plugin Portal and a few other public
     * repositories, which fetches each file from them once and which any Code Genome Project token reads. The plugin
     * portal goes to the portal's own repository beside it.
     */
    static final String CGP_URL = "https://artifacts.codegenomeproject.org/maven-public/";

    private static final String PLUGIN_PORTAL_HOST = "plugins.gradle.org";

    /**
     * The plugin portal belongs here because it answers for anything it does not host itself with a redirect
     * to Maven Central.
     */
    private static final Set<String> MAVEN_CENTRAL_HOSTS = Set.of(
            "repo.maven.apache.org",
            "repo1.maven.org",
            PLUGIN_PORTAL_HOST);

    private final String url;
    private final String pluginPortalUrl;
    private final String username;
    private final String password;

    private MavenCentralMirror(String url, String username, String password) {
        this.url = url;
        // CGP's maven-public serves a module's version list from the first repository that lists it, Central's ahead
        // of the portal's, so a plugin both list would resolve as Central has it: dependency-check's stops at 10.0.3
        // there and is at 13.0.0 on the portal. The portal alone is beside it, as gradle-plugins, which follows the
        // portal's redirects to Central. Any other mirror takes the portal's place too.
        this.pluginPortalUrl = url.replaceFirst("/maven-public/?$", "/gradle-plugins/");
        this.username = username;
        this.password = password;
    }

    /**
     * The mirror the {@code MAVEN_CENTRAL_MIRROR_*} environment variables name, or failing that the Code Genome
     * Project's Maven cache, read with the credentials {@link RewriteDependencyRepositoriesPlugin} reads the Code
     * Genome Project with. CI exports the variables when it means a mirror and credentials of its own, and so does
     * {@link #environment()} to the tests of a build that is itself mirrored, which is how a build those tests go on
     * to launch comes to be mirrored as well.
     *
     * @return {@code null} when neither is available, which leaves every repository where it was declared.
     */
    static @Nullable MavenCentralMirror ifAvailable(ProviderFactory providers) {
        String url = providers.environmentVariable("MAVEN_CENTRAL_MIRROR_URL").getOrElse("");
        String username = providers.environmentVariable("MAVEN_CENTRAL_MIRROR_USERNAME").getOrElse("");
        String password = providers.environmentVariable("MAVEN_CENTRAL_MIRROR_PASSWORD").getOrElse("");
        if (!url.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
            return new MavenCentralMirror(url, username, password);
        }
        username = RewriteDependencyRepositoriesPlugin.cgpUsername(providers);
        password = RewriteDependencyRepositoriesPlugin.cgpPassword(providers);
        if (!username.isEmpty() && !password.isEmpty()) {
            return new MavenCentralMirror(CGP_URL, username, password);
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
            repo.setUrl(PLUGIN_PORTAL_HOST.equals(repo.getUrl().getHost()) ? pluginPortalUrl : url);
            repo.credentials(credentials -> {
                credentials.setUsername(username);
                credentials.setPassword(password);
            });
            // Neither repository it stands in for ever carries snapshots, and a mirror CI names may. Gradle knows
            // that of mavenCentral() already, but not of the plugin portal or of a Central URL spelled out.
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
        return URI.create(url).equals(repo.getUrl()) || URI.create(pluginPortalUrl).equals(repo.getUrl());
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
                "MAVEN_CENTRAL_MIRROR_URL", url,
                "MAVEN_CENTRAL_MIRROR_USERNAME", username,
                "MAVEN_CENTRAL_MIRROR_PASSWORD", password);
    }
}
