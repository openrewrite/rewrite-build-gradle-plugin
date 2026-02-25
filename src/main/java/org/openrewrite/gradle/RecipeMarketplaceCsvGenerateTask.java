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
import org.gradle.api.provider.Property;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.*;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceReader;
import org.openrewrite.marketplace.RecipeMarketplaceWriter;
import org.openrewrite.maven.marketplace.MavenRecipeMarketplaceGenerator;
import org.openrewrite.maven.tree.GroupArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.stream.Collectors.toList;

public abstract class RecipeMarketplaceCsvGenerateTask extends DefaultTask {

    @Input
    public abstract Property<String> getGroupId();

    @Input
    public abstract Property<String> getArtifactId();

    @Input
    public abstract Property<String> getVersion();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRecipeJar();

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    /**
     * The output CSV file where the generated marketplace will be written.
     * Defaults to src/main/resources/META-INF/rewrite/recipes.csv
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Override
    public String getDescription() {
        return "Generates recipes.csv from recipe JAR and merges with existing CSV if present.";
    }

    @Override
    public String getGroup() {
        return "OpenRewrite";
    }

    @TaskAction
    void generate() throws IOException {
        Path recipeJarPath = getRecipeJar().get().getAsFile().toPath();
        if (!Files.exists(recipeJarPath)) {
            throw new GradleException("Recipe JAR does not exist: " + recipeJarPath + ". Make sure the jar task has run.");
        }

        // Get runtime classpath (dependencies only, excluding the recipe JAR itself)
        List<Path> classpath = getRuntimeClasspath()
                .getFiles()
                .stream()
                .map(File::toPath)
                .filter(path -> !path.equals(recipeJarPath)) // Exclude the recipe JAR itself
                .collect(toList());

        String groupId = getGroupId().get();
        String artifactId = getArtifactId().get();
        String version = getVersion().get();

        getLogger().info("Generating recipe marketplace from JAR: {}", recipeJarPath);
        getLogger().info("Using GAV coordinates: {}:{}:{}", groupId, artifactId, version);

        MavenRecipeMarketplaceGenerator generator = new MavenRecipeMarketplaceGenerator(
                new GroupArtifact(groupId, artifactId),
                recipeJarPath,
                classpath
        );
        RecipeMarketplace generated = generator.generate();

        // Check if existing CSV exists and merge if present
        Path outputPath = getOutputFile().get().getAsFile().toPath();
        RecipeMarketplace marketplace = generated;

        if (Files.exists(outputPath)) {
            getLogger().info("Found existing recipes.csv, merging...");
            RecipeMarketplaceReader reader = new RecipeMarketplaceReader();
            RecipeMarketplace existing = reader.fromCsv(outputPath);

            String packageName = groupId + ":" + artifactId;
            existing.uninstall("maven", packageName);
            existing.getRoot().merge(generated.getRoot());
            marketplace = existing;
        } else {
            getLogger().info("No existing recipes.csv found, creating new file");
        }

        // Write merged marketplace to CSV
        RecipeMarketplaceWriter writer = new RecipeMarketplaceWriter();
        String csv = writer.toCsv(marketplace);

        // Check if CSV has actual content (more than just header)
        long lineCount = csv.lines().count();
        if (lineCount <= 1) {
            getLogger().lifecycle("No recipes found, skipping recipes.csv generation");
            return;
        }

        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());

        // Write to file
        Files.writeString(outputPath, csv);
        getLogger().lifecycle("Generated recipes.csv at: {}", outputPath.toAbsolutePath());
    }
}
