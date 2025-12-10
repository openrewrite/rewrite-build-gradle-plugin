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

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class RecipeMarketplaceCsvGenerateTaskTest {
    @TempDir
    Path projectDir;

    private Path settingsFile;
    private Path buildFile;
    private Path csvFile;

    @BeforeEach
    void setup() {
        settingsFile = projectDir.resolve("settings.gradle");
        buildFile = projectDir.resolve("build.gradle");
        csvFile = Path.of(projectDir.toString(), "src", "main", "resources", "META-INF", "rewrite", "recipes.csv");
    }

    @Test
    void generatesRecipeCsvFromJar() throws Exception {
        createSimpleRecipeProject();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":jar")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Read and verify CSV content
        assertThat(csvFile)
          .content()
          .contains("name")
          .contains("org.example.TestRecipe");
    }

    @Test
    void mergesWithExistingRecipeCsv() throws Exception {
        createSimpleRecipeProject();

        // Create existing recipes.csv with custom entry
        Files.createDirectories(csvFile.getParent());
        Files.writeString(csvFile,
          //language=csv
          """
            name,displayName,packageName,ecosystem,category1
            org.example.CustomRecipe,Custom Recipe,org.example:example-custom,Maven,Custom
            """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Assert merged CSV contains both generated and existing recipes
        assertThat(csvFile)
          .content()
          .contains("org.example.TestRecipe")
          .contains("org.example.CustomRecipe")
          .contains("Custom Recipe");
    }

    @Test
    void mergesRecipesFromBothSources() throws Exception {
        createSimpleRecipeProject();

        // Create existing recipes.csv with same recipe but in different category
        Files.createDirectories(csvFile.getParent());
        Files.writeString(csvFile,
          """
            ecosystem,packageName,name,displayName,category1
            maven,org.example:test-recipe-project,org.example.TestRecipe,Old Display Name,Old Category
            """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Assert both the manual and generated entries are present (merge is additive)
        assertThat(csvFile)
          .content()
          .contains("org.example.TestRecipe")
          .contains("Old Display Name") // Manual entry preserved
          .contains("Test recipe"); // Generated entry added
    }

    @Test
    void usesNebulaPublicationForGav() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'java'
              id 'org.openrewrite.build.recipe-library-base'
              id 'org.openrewrite.build.publish'
          }

          group = 'com.example.custom'
          version = '2.0.0'

          repositories {
              mavenCentral()
          }

          dependencies {
              // Plugin provides rewrite dependencies
          }
          """);

        createSimpleRecipeClass();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Verify the GAV from nebula publication was used
        assertThat(result.getOutput())
          .contains("com.example.custom")
          .contains("2.0.0");
    }

    @Test
    void createsParentDirectoriesIfNeeded() throws Exception {
        createSimpleRecipeProject();

        // Delete the resources directory if it exists
        Path resourcesDir = Path.of(projectDir.toString(), "src", "main", "resources");
        if (Files.exists(resourcesDir)) {
            deleteDirectory(resourcesDir);
        }

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Assert parent directories were created
        assertThat(csvFile.getParent()).exists().isDirectory();
        assertThat(csvFile).exists().isNotEmptyFile();
    }

    private void createSimpleRecipeProject() throws IOException {
        createGradleBuildFiles("""
          plugins {
              id 'java'
              id 'org.openrewrite.build.recipe-library-base'
              id 'org.openrewrite.build.publish'
          }

          group = 'org.example'
          version = '1.0.0'

          repositories {
              mavenCentral()
          }

          dependencies {
              // Plugin provides rewrite dependencies
          }
          """);

        createSimpleRecipeClass();
    }

    private void createSimpleRecipeClass() throws IOException {
        // Use a declarative YAML recipe instead of Java class for simpler testing
        Path rewriteDir = Path.of(projectDir.toString(), "src", "main", "resources", "META-INF", "rewrite");
        Files.createDirectories(rewriteDir);

        @Language("yaml")
        String rewriteYml = """
          ---
          type: specs.openrewrite.org/v1beta/recipe
          name: org.example.TestRecipe
          displayName: Test recipe
          description: A test recipe for testing purposes.
          recipeList:
            - org.openrewrite.text.ChangeText:
                toText: "Hello World"
          """;

        Files.writeString(rewriteDir.resolve("rewrite.yml"), rewriteYml);
    }

    private void createGradleBuildFiles(@Language("gradle") String buildFileContent) throws IOException {
        Files.writeString(settingsFile, "rootProject.name = 'test-recipe-project'");
        Files.writeString(buildFile, buildFileContent);
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted((a, b) -> -a.compareTo(b))
                  .forEach(p -> {
                      try {
                          Files.delete(p);
                      } catch (IOException e) {
                          // Ignore
                      }
                  });
            }
        }
    }
}
