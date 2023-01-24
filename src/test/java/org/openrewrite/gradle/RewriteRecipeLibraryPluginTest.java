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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteRecipeLibraryPluginTest {
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
    public void parserClasspathDependencies() throws IOException {
        writeFile(settingsFile, "rootProject.name = 'my-project'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'org.openrewrite.build.recipe-library'
                }
                
                version = '1.0'
                
                recipeDependencies {
                    parserClasspath 'com.google.guava:guava:30.1-jre'
                    parserClasspath 'com.google.guava:guava:31.1-jre'
                }
                """;

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("downloadRecipeDependencies")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":downloadRecipeDependencies")).getOutcome());

        assertThat(projectDir.toPath().resolve("src/main/resources/META-INF/rewrite/classpath").toFile().list())
                .containsExactlyInAnyOrder("guava-30.1-jre.jar", "guava-31.1-jre.jar");
    }

    private void writeFile(File destination, String content) throws IOException {
        Files.write(destination.toPath(), content.getBytes());
    }
}
