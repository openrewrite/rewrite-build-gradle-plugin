/*
 * Copyright 2025 the original author or authors.
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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.openrewrite.Validated;
import org.openrewrite.config.ClasspathScanningLoader;
import org.openrewrite.config.Environment;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceCompletenessValidator;
import org.openrewrite.marketplace.RecipeMarketplaceReader;
import org.openrewrite.maven.marketplace.MavenRecipeMarketplaceGenerator;
import org.openrewrite.maven.marketplace.RecipeClassLoader;
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static java.util.stream.Collectors.toList;

public abstract class RecipeMarketplaceCsvValidateCompletenessTask extends DefaultTask {

    /**
     * The recipes.csv file to validate.
     * Defaults to src/main/resources/META-INF/rewrite/recipes.csv
     * <br/>
     * Note: Not marked as @InputFile because we want to allow the file to not exist
     * and gracefully skip validation in that case.
     */
    @Internal
    public abstract RegularFileProperty getCsvFile();

    public RecipeMarketplaceCsvValidateCompletenessTask() {
        getCsvFile().convention(
                getProject().getLayout().getProjectDirectory().file("src/main/resources/META-INF/rewrite/recipes.csv")
        );
    }

    @Override
    public String getDescription() {
        return "Validates the completeness of recipes.csv against the recipe JAR (CSV ↔ JAR synchronization)";
    }

    @Override
    public String getGroup() {
        return "OpenRewrite";
    }

    @TaskAction
    void validate() {
        Path csvPath = getCsvFile().get().getAsFile().toPath();

        if (!Files.exists(csvPath)) {
            getLogger().lifecycle("No recipes.csv found at {}, skipping validation", csvPath.toAbsolutePath());
            return;
        }

        // Get the jar task output
        Jar jarTask = (Jar) getProject().getTasks().getByName("jar");
        Path recipeJarPath = jarTask.getArchiveFile().get().getAsFile().toPath();

        if (!Files.exists(recipeJarPath)) {
            throw new GradleException("Recipe JAR does not exist: " + recipeJarPath + ". Make sure the jar task has run.");
        }

        getLogger().info("Validating recipes.csv completeness at: {}", csvPath.toAbsolutePath());
        getLogger().info("Against recipe JAR: {}", recipeJarPath);

        // Read the CSV
        RecipeMarketplaceReader reader = new RecipeMarketplaceReader();
        RecipeMarketplace csvMarketplace = reader.fromCsv(csvPath);

        // Get runtime classpath (dependencies only, excluding the recipe JAR itself)
        JavaPluginExtension javaExtension = getProject().getExtensions().getByType(JavaPluginExtension.class);
        List<Path> classpath = javaExtension.getSourceSets()
                .getByName("main")
                .getRuntimeClasspath()
                .getFiles()
                .stream()
                .map(File::toPath)
                .filter(path -> !path.equals(recipeJarPath)) // Exclude the recipe JAR itself
                .collect(toList());

        Environment jarEnvironment = Environment.builder()
                .load(new ClasspathScanningLoader(new Properties(), new RecipeClassLoader(recipeJarPath, classpath)))
                .build();

        // Validate completeness
        RecipeMarketplaceCompletenessValidator validator = new RecipeMarketplaceCompletenessValidator();
        Validated<RecipeMarketplace> validation = validator.validate(csvMarketplace, jarEnvironment);

        if (validation.isInvalid()) {
            List<Validated.Invalid<RecipeMarketplace>> failures = validation.failures();
            StringBuilder errorMessage = new StringBuilder();
            errorMessage.append("Recipe marketplace CSV completeness validation failed with ")
                    .append(failures.size())
                    .append(" error(s):\n");

            for (Validated.Invalid<RecipeMarketplace> failure : failures) {
                errorMessage.append("  - ").append(failure.getMessage()).append("\n");
            }

            throw new GradleException(errorMessage.toString());
        }

        getLogger().lifecycle("Recipe marketplace CSV completeness validation passed");
    }
}
