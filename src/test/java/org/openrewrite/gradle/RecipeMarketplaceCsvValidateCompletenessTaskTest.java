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

class RecipeMarketplaceCsvValidateCompletenessTaskTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;
    private File csvFile;

    @BeforeEach
    void setup() {
        settingsFile = new File(projectDir, "settings.gradle");
        buildFile = new File(projectDir, "build.gradle");
        csvFile = new File(projectDir, "src/main/resources/META-INF/rewrite/recipes.csv");
    }

    @Test
    void validatesCompletenessBetweenCsvAndJar() throws Exception {
        createSimpleRecipeProject();
        createCsvMatchingJar();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":jar")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("Recipe marketplace CSV completeness validation passed");
    }

    @Test
    void failsWhenCsvContainsPhantomRecipe() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        // CSV contains a recipe that doesn't exist in the JAR
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.
          maven,org.example:test-recipe-project,org.example.PhantomRecipe,Phantom recipe,This recipe does not exist.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("recipe(s) not listed in CSV")
          .contains("org.example.PhantomRecipe");
    }

    @Test
    void failsWhenJarContainsMissingRecipe() throws Exception {
        createProjectWithTwoRecipes();
        csvFile.getParentFile().mkdirs();
        // CSV only contains one recipe, but JAR has two
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("recipe(s) not listed in CSV")
          .contains("org.example.AnotherRecipe");
    }

    @Test
    void reportsMultipleCompletenessErrors() throws Exception {
        createProjectWithTwoRecipes();
        csvFile.getParentFile().mkdirs();
        // CSV has one phantom recipe and is missing one real recipe
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.
          maven,org.example:test-recipe-project,org.example.PhantomRecipe,Phantom recipe,Does not exist.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains(
            "Recipe listed in CSV must exist in the environment (1)",
            " - org.example.AnotherRecipe",
            "Recipe exists in environment but is not listed in CSV (1)",
            " - org.example.PhantomRecipe");
    }

    @Test
    void skipsValidationWhenCsvDoesNotExist() throws Exception {
        createSimpleRecipeProject();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":jar")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("No recipes.csv found");
    }

    @Test
    void handlesDuplicateRecipesInDifferentCategories() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        // Recipe appears in multiple categories - should be treated as single recipe
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description,category1
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.,Java
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.,Cleanup
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("Recipe marketplace CSV completeness validation passed");
    }

    @Test
    void validatesWithCategories() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description,category1,category2
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.,Example,Cleanup
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("Recipe marketplace CSV completeness validation passed");
    }

    private void createSimpleRecipeProject() throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'test-recipe-project'");
        Files.writeString(buildFile.toPath(), """
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

        createRecipeClass("TestRecipe", "Test recipe", "A test recipe.");
    }

    private void createProjectWithTwoRecipes() throws IOException {
        createSimpleRecipeProject();
        createRecipeClass("AnotherRecipe", "Another recipe", "Another test recipe.");
    }

    private void createRecipeClass(String className, String displayName, String description) throws IOException {
        // Use declarative YAML recipe for simpler testing
        File rewriteDir = new File(projectDir, "src/main/resources/META-INF/rewrite");
        rewriteDir.mkdirs();
        File rewriteYml = new File(rewriteDir, "rewrite.yml");

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

        if (rewriteYml.exists()) {
            // Append to existing file
            Files.writeString(rewriteYml.toPath(), Files.readString(rewriteYml.toPath()) + recipeYml);
        } else {
            Files.writeString(rewriteYml.toPath(), recipeYml);
        }
    }

    private void createCsvMatchingJar() throws IOException {
        csvFile.getParentFile().mkdirs();
        Files.writeString(csvFile.toPath(),
          """
            ecosystem,packageName,name,displayName,description
            maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A test recipe.
            """);
    }
}
