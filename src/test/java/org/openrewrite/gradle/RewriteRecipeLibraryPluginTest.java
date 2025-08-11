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

class RewriteRecipeLibraryPluginTest {
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
    void replaceWithNewerDependency() throws Exception {
        writeFile(settingsFile, "rootProject.name = 'my-project'");
        File cp = new File(projectDir, "src/main/resources/META-INF/rewrite/classpath");
        assertThat(cp.mkdirs()).isTrue();
        writeFile(new File(cp, "spring-boot-actuator-2.2.11.RELEASE.jar"), "test");
        writeFile(new File(cp, "spring-web-6.0.4.jar"), "test");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'org.openrewrite.build.recipe-library'
                }
                                
                version = '1.0'
                                
                recipeDependencies {
                    parserClasspath 'org.springframework.boot:spring-boot-actuator:2.+'
                }
                """;

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("downloadRecipeDependencies", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":downloadRecipeDependencies")).getOutcome());
        String[] list = cp.list();
        assertThat(list).isNotNull();
        assertThat(list.length).isEqualTo(2);
        boolean foundSpringBootActuator = false;
        boolean foundSpringWeb = false;
        for (String s : list) {
            if (s.startsWith("spring-boot-actuator")) {
                foundSpringBootActuator = true;
            } else if (s.startsWith("spring-web")) {
                foundSpringWeb = true;
            }
        }
        assertThat(foundSpringBootActuator).isTrue();
        assertThat(foundSpringWeb).isTrue();
    }

    @Test
    void parserClasspathDependencies() throws Exception {
        writeFile(settingsFile, "rootProject.name = 'my-project'");
        File cp = new File(projectDir, "src/main/resources/META-INF/rewrite/classpath");
        assertThat(cp.mkdirs()).isTrue();

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
                .withArguments("downloadRecipeDependencies", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertEquals(SUCCESS, requireNonNull(result.task(":downloadRecipeDependencies")).getOutcome());

        assertThat(cp.list()).containsExactlyInAnyOrder("guava-30.1-jre.jar", "guava-31.1-jre.jar");
    }

    private void writeFile(File destination, String content) throws IOException {
        Files.write(destination.toPath(), content.getBytes());
    }
}
