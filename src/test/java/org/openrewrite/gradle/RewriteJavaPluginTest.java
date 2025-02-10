/*
 * Copyright 2022 the original author or authors.
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
import static org.gradle.testkit.runner.TaskOutcome.NO_SOURCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteJavaPluginTest {
    @TempDir
    File testProjectDir;

    private File settingsFile;
    private File buildFile;

    @BeforeEach
    public void setup() {
        settingsFile = new File(testProjectDir, "settings.gradle");
        buildFile = new File(testProjectDir, "build.gradle");
    }

    @Test
    public void testRetry() throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'my-project'");
        Files.writeString(buildFile.toPath(),
                //language=gradle
                """
                plugins {
                    id 'org.openrewrite.build.language-library'
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments("test")
                .withPluginClasspath()
                .build();

        assertEquals(NO_SOURCE, requireNonNull(result.task(":test")).getOutcome());
    }
}
