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
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("ResultOfMethodCallIgnored")
class RecipeMarketplaceCsvGenerateTaskTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;

    @BeforeEach
    public void setup() {
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

        assertEquals(SUCCESS, requireNonNull(result.task(":jar")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvGenerate")).getOutcome());

        // Assert recipes.csv was created
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        assertThat(csvFile).exists().isFile();

        // Read and verify CSV content
        String csvContent = Files.readString(csvFile.toPath());
        assertThat(csvContent)
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
            name,displayName,category1
            org.example.CustomRecipe,Custom Recipe,Custom
            """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvGenerate")).getOutcome());

        // Assert merged CSV contains both generated and existing recipes
        String csvContent = Files.readString(csvFile.toPath());
        assertThat(csvContent)
          .contains("org.example.TestRecipe")
          .contains("org.example.CustomRecipe")
          .contains("Custom Recipe");
    }

    @Test
    void mergesRecipesFromBothSources() throws Exception {
        createSimpleRecipeProject();

        // Create existing recipes.csv with same recipe but in different category
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        csvFile.getParentFile().mkdirs();
        Files.writeString(csvFile.toPath(),
          """
            name,displayName,category1
            org.example.TestRecipe,Old Display Name,Old Category
            """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvGenerate")).getOutcome());

        // Assert both the manual and generated entries are present (merge is additive)
        String csvContent = Files.readString(csvFile.toPath());
        assertThat(csvContent)
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
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvGenerate")).getOutcome());

        // Verify the GAV from nebula publication was used
        assertThat(result.getOutput())
          .contains("com.example.custom")
          .contains("2.0.0");
    }

    @Test
    void createsParentDirectoriesIfNeeded() throws Exception {
        createSimpleRecipeProject();

        // Delete the resources directory if it exists
        File resourcesDir = new File(projectDir, "src/main/resources");
        if (resourcesDir.exists()) {
            deleteDirectory(resourcesDir.toPath());
        }

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvGenerate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvGenerate")).getOutcome());

        // Assert parent directories were created
        File csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
        assertThat(csvFile).exists().isFile();
        assertThat(csvFile.getParentFile()).exists().isDirectory();
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
