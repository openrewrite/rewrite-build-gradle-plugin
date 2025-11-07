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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

public class RewriteRecipeMarketplacePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // Register recipe marketplace CSV tasks
        project.getTasks().register("recipeCsvGenerate", RecipeMarketplaceCsvGenerateTask.class, task -> {
            task.dependsOn("jar");
        });

        project.getTasks().register("recipeCsvValidateContent", RecipeMarketplaceCsvValidateContentTask.class);

        project.getTasks().register("recipeCsvValidateCompleteness", RecipeMarketplaceCsvValidateCompletenessTask.class, task -> {
            task.dependsOn("jar");
        });

        // Create a composite task that runs both validations
        project.getTasks().register("recipeCsvValidate", task -> {
            task.setGroup("OpenRewrite");
            task.setDescription("Validates recipes.csv for both content and completeness");
            task.dependsOn("recipeCsvValidateContent", "recipeCsvValidateCompleteness");
        });

        // Configure check task when Java plugin is applied
        project.getPlugins().withType(JavaPlugin.class, javaPlugin ->
            // Add CSV validation to the check phase
            project.getTasks().named("check").configure(check -> {
                check.dependsOn("recipeCsvValidate");
            }));
    }
}
