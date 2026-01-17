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
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;

public class RewriteBestPracticesPlugin implements Plugin<Project> {
    private static final String REWRITE_PLUGIN_ID = "org.openrewrite.rewrite";
    private static final String BEST_PRACTICES_RECIPE = "org.openrewrite.recipes.rewrite.OpenRewriteRecipeBestPractices";

    @Override
    public void apply(Project project) {
        // Configure repositories
        if (!project.hasProperty("releasing")) {
            project.getRepositories().mavenLocal();
        }
        project.getRepositories().mavenCentral();

        // Apply the OpenRewrite Gradle plugin
        project.getPluginManager().apply(REWRITE_PLUGIN_ID);

        // Configure after plugin is applied
        project.getPlugins().withId(REWRITE_PLUGIN_ID, plugin -> {
            String version = project.hasProperty("releasing") ? "latest.release" : "latest.integration";
            Dependency dep = project.getDependencies().create("org.openrewrite.recipe:rewrite-rewrite:" + version);
            // Exclude transitive dependencies to avoid classpath conflicts with project's own rewrite dependencies
            ((ExternalModuleDependency) dep).setTransitive(false);
            project.getDependencies().add("rewrite", dep);
            project.getExtensions().configure(RewriteExtension.class, ext -> ext.activeRecipe(BEST_PRACTICES_RECIPE));
        });
    }
}
