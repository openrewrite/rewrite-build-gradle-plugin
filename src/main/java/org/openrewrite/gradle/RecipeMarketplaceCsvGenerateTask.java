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
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
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

    /**
     * The output CSV file where the generated marketplace will be written.
     * Defaults to src/main/resources/META-INF/rewrite/recipes.csv
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    public RecipeMarketplaceCsvGenerateTask() {
        getOutputFile().convention(
                getProject().getLayout().getProjectDirectory().file("src/main/resources/META-INF/rewrite/recipes.csv")
        );
    }

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
        // Get the jar task output
        Jar jarTask = (Jar) getProject().getTasks().getByName("jar");
        Path recipeJarPath = jarTask.getArchiveFile().get().getAsFile().toPath();
        if (!Files.exists(recipeJarPath)) {
            throw new GradleException("Recipe JAR does not exist: " + recipeJarPath + ". Make sure the jar task has run.");
        }

        // Get GAV from nebula publication
        PublishingExtension publishing = getProject().getExtensions().findByType(PublishingExtension.class);
        if (publishing == null) {
            throw new GradleException("Publishing extension not found. Make sure the publishing plugin is applied.");
        }

        MavenPublication nebulaPublication = (MavenPublication) publishing.getPublications().findByName("nebula");
        if (nebulaPublication == null) {
            throw new GradleException("Nebula publication not found. Make sure the nebula publishing plugin is applied.");
        }

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

        getLogger().info("Generating recipe marketplace from JAR: {}", recipeJarPath);
        getLogger().info("Using GAV coordinates: {}:{}:{}",
                nebulaPublication.getGroupId(),
                nebulaPublication.getArtifactId(),
                nebulaPublication.getVersion());

        MavenRecipeMarketplaceGenerator generator = new MavenRecipeMarketplaceGenerator(
                new GroupArtifact(nebulaPublication.getGroupId(), nebulaPublication.getArtifactId()),
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

            // Merge generated marketplace into existing one
            // This ensures existing data is preserved and updated with generated data
            existing.merge(generated);
            marketplace = existing;
        } else {
            getLogger().info("No existing recipes.csv found, creating new file");
        }

        // Write merged marketplace to CSV
        RecipeMarketplaceWriter writer = new RecipeMarketplaceWriter();
        String csv = writer.toCsv(marketplace);

        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());

        // Write to file
        Files.writeString(outputPath, csv);
        getLogger().lifecycle("Generated recipes.csv at: {}", outputPath.toAbsolutePath());
    }
}
