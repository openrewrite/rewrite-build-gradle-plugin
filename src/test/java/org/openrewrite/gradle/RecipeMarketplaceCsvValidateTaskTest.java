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

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class RecipeMarketplaceCsvValidateTaskTest {
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
    void runsBothContentAndCompletenessValidations() throws Exception {
        createSimpleRecipeProject();
        createValidCsv();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":jar")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidateContent")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidate")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV content validation passed")
          .contains("Recipe marketplace CSV completeness validation passed");
    }

    @Test
    void failsWhenContentValidationFails() throws Exception {
        createSimpleRecipeProject();

        // Invalid: display name doesn't start with uppercase
        Files.createDirectories(csvFile.getParent());
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-project-recipe,org.example.TestRecipe,test recipe,A test recipe.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateContent")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV content validation failed")
          .contains("Display name must be sentence cased");
    }

    @Test
    void failsWhenCompletenessValidationFails() throws Exception {
        createSimpleRecipeProject();

        // Valid content but incomplete: phantom recipe
        Files.createDirectories(csvFile.getParent());
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-project-recipe,org.example.TestRecipe,Test Recipe,A test recipe.
          maven,org.example:test-project-recipe,org.example.PhantomRecipe,Phantom Recipe,Does not exist.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        // When build fails, intermediate tasks might not be in the result
        // Check the output for validation messages instead
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV completeness validation failed")
          .contains("Recipe listed in CSV must exist in the environment");

        // The recipeCsvValidate task should have failed
        var validateTask = result.task(":recipeCsvValidate");
        if (validateTask != null) {
            assertThat(validateTask.getOutcome()).isEqualTo(FAILED);
        }
    }

    @Test
    void skipsBothValidationsWhenCsvDoesNotExist() throws Exception {
        createSimpleRecipeProject();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvValidateContent")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidate")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("No recipes.csv found");
    }

    @Test
    void canBeUsedInCheckTask() throws Exception {
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

          // Add CSV validation to check task
          check.dependsOn recipeCsvValidate
          """);

        createRecipeClass();
        createValidCsv();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("check", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":check")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidate")).getOutcome()).isEqualTo(SUCCESS);
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

        createRecipeClass();
    }

    private void createRecipeClass() throws IOException {
        // Use declarative YAML recipe for simpler testing
        Path rewriteDir = Path.of(projectDir.toString(), "src", "main", "resources", "META-INF", "rewrite");
        Files.createDirectories(rewriteDir);

        @Language("yaml")
        String rewriteYml = """
          ---
          type: specs.openrewrite.org/v1beta/recipe
          name: org.example.TestRecipe
          displayName: Test Recipe
          description: A test recipe.
          recipeList:
            - org.openrewrite.text.ChangeText:
                toText: "Hello"
          """;

        Files.writeString(rewriteDir.resolve("rewrite.yml"), rewriteYml);
    }

    private void createValidCsv() throws IOException {
        Files.createDirectories(csvFile.getParent());
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-project-recipe,org.example.TestRecipe,Test Recipe,A test recipe.
          """);
    }

    private void createGradleBuildFiles(@Language("gradle") String buildFileContent) throws IOException {
        Files.writeString(settingsFile, "rootProject.name = 'test-recipe-project'");
        Files.writeString(buildFile, buildFileContent);
    }
}
