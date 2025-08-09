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

public class RewriteDependencyRepositoriesPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RepositoryHandler repos = project.getRepositories();

        if (!project.hasProperty("releasing")) {
            repos.add(repos.mavenLocal(repo -> repo.content(content ->
                    content.excludeVersionByRegex(".+", ".+", ".+-rc[-]?[0-9]*"))));
            repos.add(repos.maven(repo -> repo.setUrl("https://central.sonatype.com/repository/maven-snapshots/")));
        }

        repos.add(repos.mavenCentral(repo -> repo.content(content ->
                content.excludeVersionByRegex(".+", ".+", ".+-rc[-]?[0-9]*"))));
    }
}
