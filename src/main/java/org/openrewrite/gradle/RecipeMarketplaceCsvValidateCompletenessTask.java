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
import org.openrewrite.marketplace.RecipeClassLoader;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceCompletenessValidator;
import org.openrewrite.marketplace.RecipeMarketplaceReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;

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

        // Validate completeness
        RecipeMarketplaceCompletenessValidator validator = new RecipeMarketplaceCompletenessValidator();
        Validated<RecipeMarketplace> validation = validator.validate(
                new RecipeMarketplaceReader().fromCsv(csvPath),
                recipeJarEnvironment(recipeJarPath));

        if (validation.isInvalid()) {
            Map<String, List<Validated.Invalid<RecipeMarketplace>>> byMessage = validation.failures().stream()
                    .sorted(comparing(Validated.Invalid::getProperty))
                    .collect(groupingBy(Validated.Invalid::getMessage));

            StringBuilder errorMessage = new StringBuilder();
            for (Map.Entry<String, List<Validated.Invalid<RecipeMarketplace>>> entry : byMessage.entrySet()) {
                String message = entry.getKey();
                List<Validated.Invalid<RecipeMarketplace>> props = entry.getValue();

                errorMessage.append(message).append(" (").append(props.size()).append("):\n");
                for (Validated.Invalid<RecipeMarketplace> failure : props) {
                    errorMessage.append("  - ").append(failure.getProperty()).append("\n");
                }
            }

            throw new GradleException(errorMessage.toString());
        }

        getLogger().lifecycle("Recipe marketplace CSV completeness validation passed");
    }

    /**
     * Construct an Environment that only loads recipes directly from the given recipe JAR, not from its dependencies.
     * This ensures that completeness validation is only done against recipes actually provided by the recipe JAR.
     */
    private Environment recipeJarEnvironment(Path recipeJarPath) {
        // Get runtime classpath, as that contains classes needed to load recipes
        JavaPluginExtension javaExtension = getProject().getExtensions().getByType(JavaPluginExtension.class);
        List<Path> classpath = javaExtension.getSourceSets()
                .getByName("main")
                .getRuntimeClasspath()
                .getFiles()
                .stream()
                .map(File::toPath)
                .filter(path -> path.toString().endsWith(".jar")) // Exclude build output directories
                .toList();

        // Load environment from JAR
        return Environment.builder()
                .load(new ClasspathScanningLoader(new Properties(), recipeJarPath, new RecipeClassLoader(recipeJarPath, classpath)))
                .build();
    }
}
