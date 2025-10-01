/*
 * Copyright 2021 the original author or authors.
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
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;

public class RewriteLanguageLibraryPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(RewriteDependencyRepositoriesPlugin.class);
        project.getPlugins().apply(RewriteJavaPlugin.class);
        project.getPlugins().apply(RewriteLicensePlugin.class);
        project.getPlugins().apply(RewriteMetadataPlugin.class);
        project.getPlugins().apply(RewriteBuildInputLoggingPlugin.class);

        project.getExtensions().create("recipeDependencies", RecipeDependenciesExtension.class);

        // Register base task for backward compatibility
        project.getTasks().register("createTypeTable", RecipeDependenciesTypeTableTask.class, task -> {
            task.getSourceSetName().convention("main");
        });

        // Configure source set specific tasks when Java plugin is applied
        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);

            javaExt.getSourceSets().all(sourceSet -> {
                String sourceSetName = sourceSet.getName();

                // Register task for each source set
                String taskName = "main".equals(sourceSetName) ? "createTypeTable" :
                        "create" + capitalize(sourceSetName) + "TypeTable";

                if (!"main".equals(sourceSetName)) {
                    project.getTasks().register(taskName, RecipeDependenciesTypeTableTask.class, task -> {
                        task.getSourceSetName().convention(sourceSetName);
                        task.getTargetDir().convention(
                            project.getLayout().getProjectDirectory().dir("src/" + sourceSetName + "/resources")
                        );
                    });
                }
            });
        });

        project.getPlugins().apply(RewritePublishPlugin.class);
    }

    private static String capitalize(String str) {
        if (str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
