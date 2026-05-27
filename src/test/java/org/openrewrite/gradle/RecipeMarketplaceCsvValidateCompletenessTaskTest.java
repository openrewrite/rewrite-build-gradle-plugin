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
          .contains("Recipe listed in CSV must exist in the environment")
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
          .contains("Recipe exists in environment but is not listed in CSV")
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
            "Recipe listed in CSV must exist in the environment",
            " - org.example.AnotherRecipe",
            "Recipe exists in environment but is not listed in CSV",
            " - org.example.PhantomRecipe");
    }

    @Test
    void failsWhenCsvDisplayNameDriftsFromRecipe() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        // displayName in CSV no longer matches the recipe's displayName
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Stale display name,A test recipe.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("CSV value drifted from the recipe")
          .contains("recipeCsvGenerate")
          .contains("org.example.TestRecipe.displayName");
    }

    @Test
    void failsWhenCsvDescriptionDriftsFromRecipe() throws Exception {
        createSimpleRecipeProject();
        csvFile.getParentFile().mkdirs();
        // description in CSV no longer matches the recipe's description
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description
          maven,org.example:test-recipe-project,org.example.TestRecipe,Test recipe,A stale description.
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("CSV value drifted from the recipe")
          .contains("recipeCsvGenerate")
          .contains("org.example.TestRecipe.description");
    }

    @Test
    void failsWhenCsvOptionDisplayNameDriftsFromRecipe() throws Exception {
        createProjectWithOptionRecipe();
        csvFile.getParentFile().mkdirs();
        // The Java recipe defines an option with displayName "Live option display" and
        // description "Live option description."; the CSV's option JSON has a stale displayName.
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description,options
          maven,org.example:test-recipe-project,org.example.RecipeWithOption,Recipe with option,A recipe that has an option.,"[{""name"":""someOption"",""type"":""String"",""displayName"":""Stale option display"",""description"":""Live option description."",""required"":true}]"
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("CSV value drifted from the recipe")
          .contains("recipeCsvGenerate")
          .contains("org.example.RecipeWithOption.options[someOption].displayName");
    }

    @Test
    void failsWhenCsvOptionDescriptionDriftsFromRecipe() throws Exception {
        createProjectWithOptionRecipe();
        csvFile.getParentFile().mkdirs();
        // CSV's option JSON has a stale description.
        Files.writeString(csvFile.toPath(),
          """
          ecosystem,packageName,name,displayName,description,options
          maven,org.example:test-recipe-project,org.example.RecipeWithOption,Recipe with option,A recipe that has an option.,"[{""name"":""someOption"",""type"":""String"",""displayName"":""Live option display"",""description"":""Stale option description."",""required"":true}]"
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("recipeCsvValidateCompleteness", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .buildAndFail();

        assertThat(requireNonNull(result.task(":recipeCsvValidateCompleteness")).getOutcome()).isEqualTo(FAILED);
        assertThat(result.getOutput())
          .contains("CSV value drifted from the recipe")
          .contains("recipeCsvGenerate")
          .contains("org.example.RecipeWithOption.options[someOption].description");
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

    /**
     * Create a project containing a Java recipe class with a single {@link org.openrewrite.Option}, so that option
     * displayName / description drift between {@code recipes.csv} and the live recipe can be exercised.
     */
    private void createProjectWithOptionRecipe() throws IOException {
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
              implementation 'org.openrewrite:rewrite-core:latest.integration'
          }
          """);

        File javaDir = new File(projectDir, "src/main/java/org/example");
        javaDir.mkdirs();
        @Language("java")
        String recipeSource = """
          package org.example;

          import org.openrewrite.Option;
          import org.openrewrite.Recipe;

          public class RecipeWithOption extends Recipe {
              @Option(displayName = "Live option display", description = "Live option description.")
              private final String someOption;

              public RecipeWithOption(String someOption) {
                  this.someOption = someOption;
              }

              public String getSomeOption() {
                  return someOption;
              }

              @Override
              public String getDisplayName() {
                  return "Recipe with option";
              }

              @Override
              public String getDescription() {
                  return "A recipe that has an option.";
              }
          }
          """;
        Files.writeString(new File(javaDir, "RecipeWithOption.java").toPath(), recipeSource);
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
