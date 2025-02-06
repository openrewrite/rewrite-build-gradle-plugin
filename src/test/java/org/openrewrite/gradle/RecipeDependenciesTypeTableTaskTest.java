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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.internal.parser.TypeTable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeDependenciesTypeTableTaskTest {
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
    void createTypeTable() throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'my-project'");
        File cp = new File(projectDir, TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(cp.mkdirs()).isTrue();

        //language=groovy
        Files.writeString(buildFile.toPath(),
                """
                        plugins {
                            id 'org.openrewrite.build.recipe-library-base'
                        }
                        repositories {
                            mavenCentral()
                        }
                        recipeDependencies {
                            parserClasspath 'com.google.guava:guava:30.1-jre'
                            parserClasspath 'com.google.guava:guava:31.1-jre'
                        }
                        """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("createTypeTable", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .build();
        System.out.println(result.getOutput());

        assertEquals(SUCCESS, requireNonNull(result.task(":createTypeTable")).getOutcome());

        assertThat(cp).isFile();
    }
}
