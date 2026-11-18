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
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import org.openrewrite.Validated;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceContentValidator;
import org.openrewrite.marketplace.RecipeMarketplaceReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@DisableCachingByDefault(because = "Validation task that checks recipe marketplace CSV content")
public abstract class RecipeMarketplaceCsvValidateContentTask extends DefaultTask {

    /**
     * The recipes.csv file to validate.
     * Defaults to src/main/resources/META-INF/rewrite/recipes.csv
     * <br/>
     * Note: Marked as @InputFiles rather than @InputFile, so that a missing file skips validation instead of failing.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCsvFile();

    /**
     * Written once validation passes, so the task stays up-to-date until its inputs change.
     */
    @OutputFile
    public abstract RegularFileProperty getMarkerFile();

    @Override
    public String getDescription() {
        return "Validates the content formatting of recipes.csv (display names, descriptions)";
    }

    @Override
    public String getGroup() {
        return "OpenRewrite";
    }

    @TaskAction
    void validate() {
        Path csvPath = getCsvFile().get().getAsFile().toPath();
        if (Files.exists(csvPath)) {
            validateCsv(csvPath);
        } else {
            getLogger().lifecycle("No recipes.csv found at {}, skipping validation", csvPath.toAbsolutePath());
        }
        writeMarker();
    }

    private void validateCsv(Path csvPath) {
        getLogger().info("Validating recipes.csv content at: {}", csvPath.toAbsolutePath());

        // Read the CSV
        RecipeMarketplaceReader reader = new RecipeMarketplaceReader();
        RecipeMarketplace marketplace = reader.fromCsv(csvPath);

        // Validate content
        RecipeMarketplaceContentValidator validator = new RecipeMarketplaceContentValidator();
        Validated<RecipeMarketplace> validation = validator.validate(marketplace);

        if (validation.isInvalid()) {
            List<Validated.Invalid<RecipeMarketplace>> failures = validation.failures();
            StringBuilder errorMessage = new StringBuilder();
            errorMessage.append(failures.size())
                    .append(" content error(s):\n");

            for (Validated.Invalid<RecipeMarketplace> failure : failures) {
                errorMessage.append("  - ").append(failure.getProperty()).append(": ").append(failure.getMessage()).append("\n");
            }

            throw new GradleException(errorMessage.toString());
        }

        getLogger().lifecycle("Recipe marketplace CSV content validation passed");
    }

    private void writeMarker() {
        try {
            Files.write(getMarkerFile().get().getAsFile().toPath(), new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
