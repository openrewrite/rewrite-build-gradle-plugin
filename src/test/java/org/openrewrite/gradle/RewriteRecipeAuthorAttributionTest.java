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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
    void javaAttribution(@TempDir Path repositoryRoot) throws IOException {
        writeSampleProject(repositoryRoot);
        BuildResult result = runGradle(repositoryRoot, "jar", "-Dorg.gradle.caching=true", "--rerun-tasks");
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionJava")).getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        File expectedOutput = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.ExampleRecipe.yml");
        assertThat(expectedOutput.exists()).isTrue();
        String contents = Files.readString(expectedOutput.toPath());
        assertThat(contents).contains("email: \"sam@moderne.io\"");
        assertThat(contents).contains("recipeName: \"org.openrewrite.ExampleRecipe\"");

        BuildResult rerunResult = runGradle(repositoryRoot, "clean", "jar", "-Dorg.gradle.caching=true");
        assertThat(requireNonNull(rerunResult.task(":rewriteRecipeAuthorAttributionJava")).getOutcome())
                .as("Task should have been cached on the first execution and retrieved from the cache after cleaning")
                .isEqualTo(TaskOutcome.FROM_CACHE);
    }

    static void writeSampleProject(Path repositoryRoot) {
        try(ZipInputStream zip = new ZipInputStream(requireNonNull(RewriteRecipeAuthorAttributionTest.class
                .getClassLoader().getResourceAsStream("sample.zip")))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if(entry.isDirectory()) {
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
