/*
 * Copyright 2024 the original author or authors.
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

import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

public class RewriteRecipeMarketplacePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RegularFile recipesCsvFile = project.getLayout().getProjectDirectory().file("src/main/resources/META-INF/rewrite/recipes.csv");

        TaskProvider<RecipeMarketplaceCsvValidateContentTask> recipeCsvValidateContent = project.getTasks().register("recipeCsvValidateContent", RecipeMarketplaceCsvValidateContentTask.class, task -> {
            task.getCsvFile().convention(recipesCsvFile);
        });

        // Register recipe marketplace CSV tasks
        project.getTasks().register("recipeCsvGenerate", RecipeMarketplaceCsvGenerateTask.class, task -> {
            task.finalizedBy(recipeCsvValidateContent);
            task.getGroupId().convention(project.provider(() -> project.getGroup().toString()));
            task.getArtifactId().convention(project.provider(project::getName));
            task.getVersion().convention(project.provider(() -> project.getVersion().toString()));
            task.getRuntimeClasspath().from(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            if (project.getPlugins().hasPlugin(ShadowJavaPlugin.class)) {
                task.getRecipeJar().convention(project.getTasks().named(ShadowJavaPlugin.SHADOW_JAR_TASK_NAME, ShadowJar.class).flatMap(Jar::getArchiveFile));
            } else {
                task.getRecipeJar().convention(project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).flatMap(Jar::getArchiveFile));
            }

            task.getOutputFile().convention(recipesCsvFile);
        });

        TaskProvider<RecipeMarketplaceCsvValidateCompletenessTask> recipeCsvValidateCompleteness = project.getTasks().register("recipeCsvValidateCompleteness", RecipeMarketplaceCsvValidateCompletenessTask.class,
                task -> {
                    task.getRuntimeClasspath().from(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
                    if (project.getPlugins().hasPlugin(ShadowJavaPlugin.class)) {
                        task.getRecipeJar().convention(project.getTasks().named(ShadowJavaPlugin.SHADOW_JAR_TASK_NAME, ShadowJar.class).flatMap(Jar::getArchiveFile));
                    } else {
                        task.getRecipeJar().convention(project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).flatMap(Jar::getArchiveFile));
                    }

                    task.getProjectPackageName().convention(project.provider(() ->
                            project.getGroup() + ":" + project.getName()));
                    task.getCsvFile().convention(recipesCsvFile);
                });

        // Create a composite task that runs both validations
        TaskProvider<Task> recipeCsvValidate = project.getTasks().register("recipeCsvValidate", task -> {
            task.setGroup("OpenRewrite");
            task.setDescription("Validates recipes.csv for both content and completeness");
            task.dependsOn(recipeCsvValidateContent, recipeCsvValidateCompleteness);
        });

        // Configure check task when Java plugin is applied
        project.getPlugins().withType(JavaPlugin.class, javaPlugin ->
            // Add CSV validation to the check phase
            project.getTasks().named("check").configure(check -> check.dependsOn(recipeCsvValidate)));
    }
}
