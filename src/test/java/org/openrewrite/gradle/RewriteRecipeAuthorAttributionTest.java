/*
 * Copyright 2023 the original author or authors.
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
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class RewriteRecipeAuthorAttributionTest {

    static BuildResult runGradle(Path buildRoot, String... args) {
        String[] argsWithStacktrace = Stream.concat(
            Arrays.stream(args),
            Stream.of("--stacktrace"))
          .toArray(String[]::new);
        return GradleRunner.create()
          .withProjectDir(buildRoot.toFile())
          .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0)
          .withArguments(argsWithStacktrace)
          .forwardOutput()
          .withPluginClasspath()
          .build();
    }

    @Test
    void attribution(@TempDir(cleanup = CleanupMode.ON_SUCCESS) Path repositoryRoot) {
        writeSampleProject(repositoryRoot);
        BuildResult result = runGradle(repositoryRoot, "jar", "-Dorg.gradle.caching=true", "--rerun-tasks");

        // Ensure Java attribution task is executed successfully
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionJava")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        File expectedOutput = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.ExampleRecipe.yml");
        //language=YAML
        assertThat(expectedOutput).hasContent("""
          ---
          type: "specs.openrewrite.org/v1beta/attribution"
          recipeName: "org.openrewrite.ExampleRecipe"
          contributors:
          - name: "Sam Snyder"
            email: "sam@moderne.io"
            lineCount: 14
          """);


        // Ensure YAML attribution task is executed successfully
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        expectedOutput = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo.yml");
        //language=YAML
        assertThat(expectedOutput).hasContent("""
          ---
          type: "specs.openrewrite.org/v1beta/attribution"
          recipeName: "org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo"
          contributors:
          - name: "Tim te Beek"
            email: "tim@moderne.io"
            lineCount: 11
          - name: "Jente"
            email: "jente@moderne.io"
            lineCount: 4
          - name: "Pierre Delagrave"
            email: "pierre@moderne.io"
            lineCount: 2
          """
        );

        // Ensure running the task again retrieves the cached result
        BuildResult rerunResult = runGradle(repositoryRoot, "clean", "jar", "-Dorg.gradle.caching=true");
        assertThat(requireNonNull(rerunResult.task(":rewriteRecipeAuthorAttributionJava")).getOutcome())
          .as("Task should have been cached on the first execution and retrieved from the cache after cleaning")
          .isEqualTo(TaskOutcome.FROM_CACHE);
        assertThat(requireNonNull(rerunResult.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .as("Task should have been cached on the first execution and retrieved from the cache after cleaning")
          .isEqualTo(TaskOutcome.FROM_CACHE);
    }

    @Test
    void removeOneImperativeRecipe(@TempDir(cleanup = CleanupMode.ON_SUCCESS) Path repositoryRoot) throws IOException {
        writeSampleProject(repositoryRoot);
        BuildResult result = runGradle(repositoryRoot, "jar");
        
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        
        File exampleRecipeAttributions = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.ExampleRecipe.yml");
        assertThat(exampleRecipeAttributions).exists();
        
        // Move instead of delete, because if there's no more files in the directory the task won't execute.
        Files.move(
          repositoryRoot.resolve("src/main/java/org/openrewrite/ExampleRecipe.java"),
          repositoryRoot.resolve("src/main/java/org/openrewrite/file.name")
        );
        
        result = runGradle(repositoryRoot, "jar");
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        
        assertThat(exampleRecipeAttributions).doesNotExist();
    }
    
    @Test
    void removeOneDeclarativeRecipeFromYaml(@TempDir(cleanup = CleanupMode.ON_SUCCESS) Path repositoryRoot) throws IOException {
        writeSampleProject(repositoryRoot);
        BuildResult result = runGradle(repositoryRoot, "jar");
        
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        
        File testRecipeAttributions = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.java.migrate.Test.yml");
        //language=yml
        assertThat(testRecipeAttributions).hasContent("""
          ---
          type: "specs.openrewrite.org/v1beta/attribution"
          recipeName: "org.openrewrite.java.migrate.Test"
          contributors:
          - name: "Pierre Delagrave"
            email: "pierre@moderne.io"
            lineCount: 9
          """);
        
        Path recipeYmlPath = repositoryRoot.resolve("src/main/resources/META-INF/rewrite/recipe.yml");
        var with2ndRecipeRemoved = Files.readAllLines(recipeYmlPath).stream()
          .takeWhile(line -> !line.contains("# RecipeToDelete"))
          .collect(Collectors.toList());
        Files.write(recipeYmlPath, with2ndRecipeRemoved);
        
        result = runGradle(repositoryRoot, "jar");
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        
        assertThat(testRecipeAttributions).doesNotExist();
    }
    
    @Test
    void removeEntireDeclarativeYamlFile(@TempDir(cleanup = CleanupMode.ON_SUCCESS) Path repositoryRoot) throws IOException {
        writeSampleProject(repositoryRoot);
        BuildResult result = runGradle(repositoryRoot, "jar");
        
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        
        File jacocoRecipeAttributions = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo.yml");
        File testRecipeAttributions = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.java.migrate.Test.yml");
        
        assertThat(jacocoRecipeAttributions).exists();
        assertThat(testRecipeAttributions).exists();
        
        Files.delete(repositoryRoot.resolve("src/main/resources/META-INF/rewrite/recipe.yml"));
        // Because if there's no YMLs to process the task won't execute.
        Files.createFile(repositoryRoot.resolve("src/main/resources/META-INF/rewrite/empty.yml"));
        
        result = runGradle(repositoryRoot, "jar");
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionResources")).getOutcome())
          .isEqualTo(TaskOutcome.SUCCESS);
        
        assertThat(jacocoRecipeAttributions).doesNotExist();
        assertThat(testRecipeAttributions).doesNotExist();
    }
    
    static void writeSampleProject(Path repositoryRoot) {
        try (ZipInputStream zip = new ZipInputStream(requireNonNull(RewriteRecipeAuthorAttributionTest.class
          .getClassLoader().getResourceAsStream("sample.zip")))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path path = repositoryRoot.resolve(entry.getName());
                //noinspection ResultOfMethodCallIgnored
                path.getParent().toFile().mkdirs();

                try (OutputStream outputStream = Files.newOutputStream(path)) {
                    zip.transferTo(outputStream);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
