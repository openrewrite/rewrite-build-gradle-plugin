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

public class RewriteDependencyRepositoriesPlugin implements Plugin<Project> {

    private static final String CGP_URL = "https://artifacts.codegenomeproject.org/maven";

    /**
     * Third-party forks such as jgit, lombok and java-object-diff that only ever shipped to Maven Central,
     * so they are neither served by CGP nor covered by the Maven Central exclusions below.
     */
    private static final String TOOLS_GROUP = "org.openrewrite.tools";

    @Override
    public void apply(Project project) {
        RepositoryHandler repos = project.getRepositories();
        boolean releasing = project.hasProperty("releasing");

        if (!releasing) {
            repos.add(repos.mavenLocal(repo -> repo.content(content ->
                    content.excludeVersionByRegex(".+", ".+", ".+-rc[-]?[0-9]*"))));
        }

        ProviderFactory providers = project.getProviders();
        String cgpUsername = providers.gradleProperty("codegenomeUsername").getOrElse("");
        String cgpPassword = providers.gradleProperty("codegenomePassword").getOrElse("");
        boolean cgpConfigured = !cgpUsername.isEmpty() && !cgpPassword.isEmpty();
        if (cgpConfigured) {
            // CGP is the first publish target for both snapshots and releases, so consult it
            // ahead of Sonatype and Maven Central; group-scoped to the artifacts it serves.
            repos.add(repos.maven(repo -> {
                repo.setName("codegenome");
                repo.setUrl(CGP_URL);
                repo.credentials(creds -> {
                    creds.setUsername(cgpUsername);
                    creds.setPassword(cgpPassword);
                });
                repo.content(content -> {
                    content.includeGroupAndSubgroups("org.openrewrite");
                    content.includeGroupAndSubgroups("io.moderne");
                    content.excludeGroupAndSubgroups(TOOLS_GROUP);
                });
            }));
        }

        if (!releasing) {
            repos.add(repos.maven(repo -> {
                repo.setUrl("https://central.sonatype.com/repository/maven-snapshots/");
                // Only consult the snapshots repo for groups that actually publish snapshots we consume,
                // so a snapshots-repo outage can't break resolution of third-party releases.
                repo.content(content -> {
                    content.includeGroupAndSubgroups("org.openrewrite");
                    content.includeGroupAndSubgroups("io.moderne");
                });
            }));
        }

        repos.add(repos.mavenCentral(repo -> repo.content(content -> {
            content.excludeVersionByRegex(".+", ".+", ".+-rc[-]?[0-9]*");
            if (cgpConfigured) {
                // Maven Central is no longer a publish target for these groups, so anything it still
                // serves is stale; resolve them from CGP (or mavenLocal) or not at all. Without CGP
                // credentials Central remains the fallback, so outside contributors can still build.
                content.excludeGroupAndSubgroups("io.moderne");
                content.excludeGroup("org.openrewrite");
                content.excludeGroupByRegex("org\\.openrewrite\\.(?!tools).*");
            }
        })));
    }
}
