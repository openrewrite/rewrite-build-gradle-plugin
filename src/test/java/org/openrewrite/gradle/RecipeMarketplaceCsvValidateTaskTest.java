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

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeMarketplaceCsvValidateTaskTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;
    private File csvFile;

    @BeforeEach
    public void setup() {
        settingsFile = new File(projectDir, "settings.gradle");
        buildFile = new File(projectDir, "build.gradle");
        csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
    }

    @Test
    void runsBothContentAndCompletenessValidations() throws Exception {
        createSimpleRecipeProject();
        createValidCsv();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":jar")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateContent")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidate")).getOutcome());
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV content validation passed")
          .contains("Recipe marketplace CSV completeness validation passed");
    }

    @Test
    void failsWhenContentValidationFails() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        // Invalid: display name doesn't start with uppercase
        Files.writeString(csvFile.toPath(),
          """
          name,displayName,description
          org.example.TestRecipe,test recipe,A test recipe.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertEquals(FAILED, requireNonNull(result.task(":recipeCsvValidateContent")).getOutcome());
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV content validation failed")
          .contains("Display name must start with an uppercase letter");
    }

    @Test
    void failsWhenCompletenessValidationFails() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        // Valid content but incomplete: phantom recipe
        Files.writeString(csvFile.toPath(),
          """
          name,displayName,description
          org.example.TestRecipe,Test Recipe,A test recipe.
          org.example.PhantomRecipe,Phantom Recipe,Does not exist.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        // When build fails, intermediate tasks might not be in the result
        // Check the output for validation messages instead
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV completeness validation failed")
          .contains("org.example.PhantomRecipe");

        // The recipeCsvValidate task should have failed
        var validateTask = result.task(":recipeCsvValidate");
        if (validateTask != null) {
            assertEquals(FAILED, validateTask.getOutcome());
        }
    }

    @Test
    void skipsBothValidationsWhenCsvDoesNotExist() throws Exception {
        createSimpleRecipeProject();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidate", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateContent")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidate")).getOutcome());
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
          .withProjectDir(projectDir)
          .withArguments("check", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":check")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidate")).getOutcome());
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
        File rewriteDir = new File(projectDir, "src/main/resources/META-INF/rewrite");
        rewriteDir.mkdirs();

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

        Files.writeString(new File(rewriteDir, "rewrite.yml").toPath(), rewriteYml);
    }

    private void createValidCsv() throws IOException {
        csvFile.getParentFile().mkdirs();
        Files.writeString(csvFile.toPath(),
          """
          name,displayName,description
          org.example.TestRecipe,Test Recipe,A test recipe.
          """);
    }

    private void createGradleBuildFiles(@Language("gradle") String buildFileContent) throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'test-recipe-project'");
        Files.writeString(buildFile.toPath(), buildFileContent);
    }
}
