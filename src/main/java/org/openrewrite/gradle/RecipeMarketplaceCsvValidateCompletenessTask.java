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
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.marketplace.RecipeClassLoader;
import org.openrewrite.marketplace.RecipeListing;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceCompletenessValidator;
import org.openrewrite.marketplace.RecipeMarketplaceReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
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

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRecipeJar();

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @Override
    public String getDescription() {
        return "Validates the completeness of recipes.csv against the recipe JAR (CSV ↔ JAR synchronization), including drift in display names and descriptions";
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

        Path recipeJarPath = getRecipeJar().get().getAsFile().toPath();

        if (!Files.exists(recipeJarPath)) {
            throw new GradleException("Recipe JAR does not exist: " + recipeJarPath + ". Make sure the jar task has run.");
        }

        getLogger().info("Validating recipes.csv completeness at: {}", csvPath.toAbsolutePath());
        getLogger().info("Against recipe JAR: {}", recipeJarPath);

        // Validate completeness
        RecipeMarketplace marketplace = new RecipeMarketplaceReader().fromCsv(csvPath);
        Environment environment = jarScanningEnvironment(recipeJarPath);
        RecipeMarketplaceCompletenessValidator validator = new RecipeMarketplaceCompletenessValidator();
        Validated<RecipeMarketplace> validation = validator.validate(marketplace, environment);

        // Detect field-level drift between CSV rows and the live recipes in the environment
        validation = validation.and(validateNoDrift(marketplace, environment));

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
     * Compare each CSV row's display name and description to the live values returned by the corresponding recipe
     * in the environment, so that silent drift between source code and {@code recipes.csv} is caught at build time.
     * <p>
     * Rows whose recipe name is not present in the environment are skipped here; the completeness validator
     * already flags them.
     */
    private Validated<RecipeMarketplace> validateNoDrift(RecipeMarketplace marketplace, Environment env) {
        Map<String, RecipeDescriptor> byName = new HashMap<>();
        for (RecipeDescriptor descriptor : env.listRecipeDescriptors()) {
            byName.put(descriptor.getName(), descriptor);
        }

        Validated<RecipeMarketplace> validation = Validated.none();
        for (RecipeListing listing : marketplace.getAllRecipes()) {
            RecipeDescriptor descriptor = byName.get(listing.getName());
            if (descriptor == null) {
                continue;
            }
            if (!Objects.equals(listing.getDisplayName(), descriptor.getDisplayName())) {
                validation = validation.and(Validated.invalid(
                        listing.getName() + ".displayName",
                        listing.getDisplayName(),
                        "CSV value drifted from the recipe; " +
                                "run `./gradlew recipeCsvGenerate` to update `recipes.csv`."));
            }
            if (!Objects.equals(listing.getDescription(), descriptor.getDescription())) {
                validation = validation.and(Validated.invalid(
                        listing.getName() + ".description",
                        listing.getDescription(),
                        "CSV value drifted from the recipe; " +
                                "run `./gradlew recipeCsvGenerate` to update `recipes.csv`."));
            }
            validation = validation.and(validateOptions(listing.getName(), listing.getOptions(), descriptor.getOptions()));
        }
        return validation;
    }

    /**
     * Compare CSV-listed options to the live options on the recipe descriptor. Options are matched by name; options
     * only present on one side are skipped (the recipe's own input validation will surface those once invoked, and
     * including them here would conflate "drift" with "set of options changed").
     */
    private Validated<RecipeMarketplace> validateOptions(String recipeName,
                                                          List<OptionDescriptor> csvOptions,
                                                          List<OptionDescriptor> liveOptions) {
        if (csvOptions.isEmpty() || liveOptions.isEmpty()) {
            return Validated.none();
        }
        Map<String, OptionDescriptor> liveByName = new HashMap<>();
        for (OptionDescriptor live : liveOptions) {
            liveByName.put(live.getName(), live);
        }
        Validated<RecipeMarketplace> validation = Validated.none();
        for (OptionDescriptor csv : csvOptions) {
            OptionDescriptor live = liveByName.get(csv.getName());
            if (live == null) {
                continue;
            }
            if (!Objects.equals(csv.getDisplayName(), live.getDisplayName())) {
                validation = validation.and(Validated.invalid(
                        recipeName + ".options[" + csv.getName() + "].displayName",
                        csv.getDisplayName(),
                        "CSV value drifted from the recipe; " +
                                "run `./gradlew recipeCsvGenerate` to update `recipes.csv`."));
            }
            if (!Objects.equals(csv.getDescription(), live.getDescription())) {
                validation = validation.and(Validated.invalid(
                        recipeName + ".options[" + csv.getName() + "].description",
                        csv.getDescription(),
                        "CSV value drifted from the recipe; " +
                                "run `./gradlew recipeCsvGenerate` to update `recipes.csv`."));
            }
        }
        return validation;
    }

    /**
     * Construct an Environment that only loads recipes directly from the given recipe JAR, not from its dependencies.
     * This ensures that completeness validation is only done against recipes actually provided by the recipe JAR.
     */
    private Environment jarScanningEnvironment(Path recipeJarPath) {
        // Get runtime classpath, as that contains classes needed to load recipes
        List<Path> classpath = getRuntimeClasspath()
                .getFiles()
                .stream()
                .map(File::toPath)
                .filter(path -> path.toString().endsWith(".jar")) // Exclude build output directories
                .collect(toList());

        // Load environment from JAR
        return Environment.builder().scanJar(recipeJarPath, classpath, new RecipeClassLoader(recipeJarPath, classpath)).build();
    }
}
