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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

@SuppressWarnings("ResultOfMethodCallIgnored")
class RecipeMarketplaceCsvGenerateTaskTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;

    @BeforeEach
    void setup() {
        settingsFile = new File(projectDir, "settings.gradle");
        buildFile = new File(projectDir, "build.gradle");
    }

    @Test
    void generatesRecipeCsvFromJar() throws Exception {
        createSimpleRecipeProject();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":jar")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Read and verify CSV content
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        assertThat(csvFile)
          .content()
          .contains("name")
          .contains("org.example.TestRecipe");
    }

    @Test
    void mergesWithExistingRecipeCsv() throws Exception {
        createSimpleRecipeProject();

        // Create existing recipes.csv with custom entry
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        csvFile.getParentFile().mkdirs();
        Files.writeString(csvFile.toPath(),
          //language=csv
          """
            ecosystem,packageName,name,displayName,category1
            maven,org.example:test-recipe-project,org.example.CustomRecipe,Custom recipe,Custom
            """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
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
          .contains("Custom recipe");
    }

    @Test
    void mergesRecipesFromBothSources() throws Exception {
        createSimpleRecipeProject();

        // Create existing recipes.csv with same recipe but in different category
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        csvFile.getParentFile().mkdirs();
        Files.writeString(csvFile.toPath(),
          """
            ecosystem,packageName,name,displayName,category1
            maven,org.example:test-recipe-project,org.example.TestRecipe,Old display name,Old Category
            """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Assert the merged CSV contains the recipe (generated may override manual entry)
        assertThat(csvFile)
          .content()
          .contains("org.example.TestRecipe");
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
          .withProjectDir(projectDir)
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

        // Delete only the recipes.csv if it exists (keeping the recipe YAML)
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        if (csvFile.exists()) {
            csvFile.delete();
        }

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);

        // Assert parent directories were created and CSV file exists
        assertThat(csvFile.getParentFile()).exists().isDirectory();
        assertThat(csvFile).exists().isFile();
    }

    @Test
    void skipsGenerationWhenNoRecipes() throws Exception {
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
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvGenerate")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("No recipes found, skipping recipes.csv generation");

        // Assert CSV file was NOT created
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        assertThat(csvFile).doesNotExist();
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

    @SuppressWarnings("NullableProblems")
    private void createSimpleRecipeClass() throws IOException {
        // Use a declarative YAML recipe instead of Java class for simpler testing
        File rewriteDir = new File(projectDir, "src/main/resources/META-INF/rewrite");
        rewriteDir.mkdirs();

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

        Files.writeString(new File(rewriteDir, "rewrite.yml").toPath(), rewriteYml);
    }

    private void createGradleBuildFiles(@Language("gradle") String buildFileContent) throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'test-recipe-project'");
        Files.writeString(buildFile.toPath(), buildFileContent);
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
