/*
 * Copyright 2022 the original author or authors.
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
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.provider.ProviderFactory;
import org.openrewrite.maven.tree.MavenRepository;

import java.util.ArrayList;
import java.util.List;

public class RewriteDependencyRepositoriesPlugin implements Plugin<Project> {

    static final String ID = "org.openrewrite.build.recipe-repositories";
    static final String CGP_ID = "codegenome";
    static final String CGP_URL = "https://artifacts.codegenomeproject.org/maven";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(RewriteArtifactoryMirrorPlugin.class);
        project.getDependencies().getComponents().all(PreReleaseStatusRule.class);

        RepositoryHandler repos = project.getRepositories();
        boolean releasing = project.hasProperty("releasing");

        if (!releasing) {
            repos.add(repos.mavenLocal());
        }

        String cgpUsername = cgpUsername(project);
        String cgpPassword = cgpPassword(project);
        repos.add(repos.maven(repo -> {
            repo.setName(CGP_ID);
            repo.setUrl(CGP_URL);
            if (cgpConfigured(cgpUsername, cgpPassword)) {
                repo.credentials(creds -> {
                    creds.setUsername(cgpUsername);
                    creds.setPassword(cgpPassword);
                });
            }
            repo.content(content -> {
                content.includeGroupAndSubgroups("org.openrewrite");
                content.includeGroupAndSubgroups("io.moderne");
            });
        }));

        repos.add(repos.mavenCentral(repo -> repo.content(content -> {
            content.excludeGroupAndSubgroups("org.openrewrite");
            content.excludeGroupAndSubgroups("io.moderne");
        })));
    }

    /**
     * The repositories {@link #apply} configures, modelled for OpenRewrite's {@code MavenPomDownloader}:
     * CGP first, then Maven Central, which everything CGP does not host still comes from — by way of the
     * Artifactory mirror when there are credentials for that.
     * {@code mavenLocal()} is left out, so that alignment follows published versions rather than whatever
     * local builds have installed.
     */
    static List<MavenRepository> pomDownloaderRepositories(Project project) {
        List<MavenRepository> repositories = new ArrayList<>();
        String cgpUsername = cgpUsername(project);
        String cgpPassword = cgpPassword(project);
        boolean cgpConfigured = cgpConfigured(cgpUsername, cgpPassword);
        repositories.add(new MavenRepository(CGP_ID, CGP_URL, "true", "true", true,
                cgpConfigured ? cgpUsername : null, cgpConfigured ? cgpPassword : null, null, false));
        ArtifactoryMirror mirror = ArtifactoryMirror.ifAvailable(project.getProviders());
        repositories.add(mirror == null ? MavenRepository.MAVEN_CENTRAL : mirror.asMavenCentral());
        return repositories;
    }

    // codeGenomeUsername and codeGenomeToken are the names docs.openrewrite.org and the Code Genome Project's
    // own setup instructions give, so a build set up from either finds its credentials here too
    private static String cgpUsername(Project project) {
        return firstGradleProperty(project, "codegenomeUsername", "codeGenomeUsername");
    }

    private static String cgpPassword(Project project) {
        return firstGradleProperty(project, "codegenomePassword", "codeGenomeToken");
    }

    private static String firstGradleProperty(Project project, String... names) {
        ProviderFactory providers = project.getProviders();
        for (String name : names) {
            String value = providers.gradleProperty(name).getOrElse("");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static boolean cgpConfigured(String cgpUsername, String cgpPassword) {
        return !cgpUsername.isEmpty() && !cgpPassword.isEmpty();
    }
}
