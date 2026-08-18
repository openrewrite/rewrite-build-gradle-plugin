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
}
