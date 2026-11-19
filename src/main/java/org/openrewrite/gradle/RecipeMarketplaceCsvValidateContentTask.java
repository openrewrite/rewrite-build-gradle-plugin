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
import org.openrewrite.Validated;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceContentValidator;
import org.openrewrite.marketplace.RecipeMarketplaceReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@CacheableTask
public abstract class RecipeMarketplaceCsvValidateContentTask extends DefaultTask {

    /**
     * The recipes.csv file to validate.
     * Defaults to src/main/resources/META-INF/rewrite/recipes.csv
     * <br/>
     * A missing file makes the task {@code NO-SOURCE} through {@link SkipWhenEmpty}.
     * It is {@link InputFiles} because {@link InputFile} fails the build when the file is missing.
     */
    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCsvFile();

    /**
     * Written when validation passes.
     * Gradle needs a declared output to treat the task as up to date or to restore it from the build cache.
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
    void validate() throws IOException {
        Path csvPath = getCsvFile().get().getAsFile().toPath();
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

        getLogger().info("Recipe marketplace CSV content validation passed");
        Files.write(getMarkerFile().get().getAsFile().toPath(), new byte[0]);
    }
}
