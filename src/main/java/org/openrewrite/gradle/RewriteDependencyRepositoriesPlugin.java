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

    static final String CGP_ID = "codegenome";
    static final String CGP_URL = "https://artifacts.codegenomeproject.org/maven";
    static final String SNAPSHOTS_ID = "central-snapshots";
    static final String SNAPSHOTS_URL = "https://central.sonatype.com/repository/maven-snapshots/";

    @Override
    public void apply(Project project) {
        RepositoryHandler repos = project.getRepositories();
        boolean releasing = project.hasProperty("releasing");

        if (!releasing) {
            repos.add(repos.mavenLocal(repo -> repo.content(content ->
                    content.excludeVersionByRegex(".+", ".+", ".+-rc[-]?[0-9]*"))));
        }

        String cgpUsername = cgpUsername(project);
        String cgpPassword = cgpPassword(project);
        boolean cgpConfigured = cgpConfigured(cgpUsername, cgpPassword);
        if (cgpConfigured) {
            repos.add(repos.maven(repo -> {
                repo.setName(CGP_ID);
                repo.setUrl(CGP_URL);
                repo.credentials(creds -> {
                    creds.setUsername(cgpUsername);
                    creds.setPassword(cgpPassword);
                });
                repo.content(content -> {
                    content.includeGroupAndSubgroups("org.openrewrite");
                    content.includeGroupAndSubgroups("io.moderne");
                });
            }));
        }

        if (!releasing) {
            repos.add(repos.maven(repo -> {
                repo.setUrl(SNAPSHOTS_URL);
                repo.content(content -> {
                    content.includeGroupAndSubgroups("org.openrewrite");
                    content.includeGroupAndSubgroups("io.moderne");
                });
            }));
        }

        repos.add(repos.mavenCentral(repo -> repo.content(content -> {
            content.excludeVersionByRegex(".+", ".+", ".+-rc[-]?[0-9]*");
            if (cgpConfigured) {
                content.excludeGroupAndSubgroups("org.openrewrite");
                content.excludeGroupAndSubgroups("io.moderne");
            }
        })));
    }

    /**
     * The repositories {@link #apply} configures, modelled for OpenRewrite's {@code MavenPomDownloader}.
     * {@code mavenLocal()} is left out: the downloader has no equivalent of its release-candidate exclusion,
     * so including it would resolve versions Gradle itself refuses.
     */
    static List<MavenRepository> pomDownloaderRepositories(Project project) {
        List<MavenRepository> repositories = new ArrayList<>();
        String cgpUsername = cgpUsername(project);
        String cgpPassword = cgpPassword(project);
        if (cgpConfigured(cgpUsername, cgpPassword)) {
            repositories.add(new MavenRepository(CGP_ID, CGP_URL, "true", "true", true, cgpUsername, cgpPassword, null, false));
        }
        if (!project.hasProperty("releasing")) {
            repositories.add(new MavenRepository(SNAPSHOTS_ID, SNAPSHOTS_URL, "false", "true", true, null, null, null, false));
        }
        repositories.add(MavenRepository.MAVEN_CENTRAL);
        return repositories;
    }

    private static String cgpUsername(Project project) {
        return gradleProperty(project, "codegenomeUsername");
    }

    private static String cgpPassword(Project project) {
        return gradleProperty(project, "codegenomePassword");
    }

    private static String gradleProperty(Project project, String name) {
        ProviderFactory providers = project.getProviders();
        return providers.gradleProperty(name).getOrElse("");
    }

    private static boolean cgpConfigured(String cgpUsername, String cgpPassword) {
        return !cgpUsername.isEmpty() && !cgpPassword.isEmpty();
    }
}
