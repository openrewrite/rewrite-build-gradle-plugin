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
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeMarketplaceCsvValidateCompletenessTaskTest {
    @TempDir
    Path projectDir;

    private Path settingsFile;
    private Path buildFile;
    private Path csvFile;

    @BeforeEach
    public void setup() throws Exception {
        settingsFile = projectDir.resolve("settings.gradle");
        buildFile = projectDir.resolve("build.gradle");
        csvFile = Path.of(projectDir.toString(), "src", "main", "resources", "META-INF", "rewrite", "recipes.csv");
        Files.createDirectories(csvFile.getParent());
    }

    @Test
    void validatesCompletenessBetweenCsvAndJar() throws Exception {
        createSimpleRecipeProject();
        createCsvMatchingJar();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":jar")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput()).contains("Recipe marketplace CSV completeness validation passed");
    }

    @Test
    void failsWhenCsvContainsPhantomRecipe() throws Exception {
        createSimpleRecipeProject();
        // CSV contains a recipe that doesn't exist in the JAR
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.
          maven,org.example:test-recipe-project,org.example.PhantomRecipe,Phantom Recipe,This recipe does not exist.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertEquals(FAILED, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV completeness validation failed with 1 error(s)")
          .contains("Recipe 'org.example.PhantomRecipe' listed in CSV must exist in the environment");
    }

    @Test
    void failsWhenJarContainsMissingRecipe() throws Exception {
        createProjectWithTwoRecipes();
        // CSV only contains one recipe, but JAR has two
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertEquals(FAILED, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV completeness validation failed with 1 error(s)")
          .contains("Recipe 'org.example.AnotherRecipe' exists in environment but is not listed in CSV");
    }

    @Test
    void reportsMultipleCompletenessErrors() throws Exception {
        createProjectWithTwoRecipes();
        // CSV has one phantom recipe and is missing one real recipe
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.
          maven,org.example:test-recipe-project,org.example.PhantomRecipe,Phantom Recipe,Does not exist.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertEquals(FAILED, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput())
          .contains("Recipe marketplace CSV completeness validation failed")
          .contains("org.example.PhantomRecipe")
          .contains("org.example.AnotherRecipe");
    }

    @Test
    void skipsValidationWhenCsvDoesNotExist() throws Exception {
        createSimpleRecipeProject();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":jar")).getOutcome());
        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput()).contains("No recipes.csv found");
    }

    @Test
    void handlesDuplicateRecipesInDifferentCategories() throws Exception {
        createSimpleRecipeProject();
        // Recipe appears in multiple categories - should be treated as single recipe
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description,category1
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.,Java
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.,Cleanup
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput()).contains("Recipe marketplace CSV completeness validation passed");
    }

    @Test
    void validatesWithCategories() throws Exception {
        createSimpleRecipeProject();
        Files.writeString(csvFile,
          """
          ecosystem,packageName,name,displayName,description,category1,category2
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.,Example,Cleanup
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir.toFile())
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome());
        assertThat(result.getOutput()).contains("Recipe marketplace CSV completeness validation passed");
    }

    private void createSimpleRecipeProject() throws IOException {
        Files.writeString(settingsFile, "rootProject.name = 'test-recipe-project'");
        Files.writeString(buildFile, """
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

        createRecipeClass("TestRecipe", "Test Recipe", "A test recipe.");
    }

    private void createProjectWithTwoRecipes() throws IOException {
        createSimpleRecipeProject();
        createRecipeClass("AnotherRecipe", "Another Recipe", "Another test recipe.");
    }

    private void createRecipeClass(String className, String displayName, String description) throws IOException {
        // Use declarative YAML recipe for simpler testing
        Path rewriteDir = Path.of(projectDir.toString(), "src", "main", "resources", "META-INF", "rewrite");
        Files.createDirectories(rewriteDir);
        Path rewriteYml = rewriteDir.resolve("rewrite.yml");

        @Language("yaml")
        String recipeYml = """
          ---
          type: specs.openrewrite.org/v1beta/recipe
          name: org.example.%s
          displayName: %s
          description: %s
          recipeList:
            - org.openrewrite.text.ChangeText:
                toText: "Hello"
          """.formatted(className, displayName, description);

        if (Files.exists(rewriteYml)) {
            // Append to existing file
            Files.writeString(rewriteYml, Files.readString(rewriteYml) + recipeYml);
        } else {
            Files.writeString(rewriteYml, recipeYml);
        }
    }

    private void createCsvMatchingJar() throws IOException {
        Files.writeString(csvFile,
          """
            ecosystem,packageName,name,displayName,description
            maven,org:example:test-recipe-project,org.example.TestRecipe,Test Recipe,A test recipe.
            """);
    }
}
