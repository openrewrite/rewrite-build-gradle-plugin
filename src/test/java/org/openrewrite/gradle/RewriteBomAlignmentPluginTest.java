/*
 * Copyright 2026 the original author or authors.
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

class RewriteBomAlignmentPluginTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;
    private File repoDir;

    @BeforeEach
    void setup() throws IOException {
        settingsFile = new File(projectDir, "settings.gradle");
        buildFile = new File(projectDir, "build.gradle");
        repoDir = new File(projectDir, "local-repo");

        // Two versions of a managed transitive, plus two intermediates that pin different versions.
        // Group must be a subgroup of org.openrewrite (e.g. org.openrewrite.recipe) — the plugin's
        // managed-group filter requires a trailing dot, so the bare "org.openrewrite" group is ignored.
        publishPom("org.openrewrite.recipe", "core", "1.0.0", "");
        publishPom("org.openrewrite.recipe", "core", "2.0.0", "");
        publishPom("org.openrewrite.recipe", "bar", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """);
        publishPom("org.openrewrite.recipe", "baz", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
                """);
    }

    @Test
    void passesWhenTransitiveVersionsAlign() throws Exception {
        writeFile(settingsFile, "rootProject.name = 'aligned-bom'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(requireNonNull(result.task(":checkBomAlignment")).getOutcome()).isEqualTo(SUCCESS);
    }

    @Test
    void failsWhenTransitiveVersionsConflict() throws Exception {
        writeFile(settingsFile, "rootProject.name = 'misaligned-bom'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                    api 'org.openrewrite.recipe:baz:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        assertThat(result.getOutput())
                .contains("BOM dependency version mismatches")
                .contains("org.openrewrite.recipe:core")
                .contains("1.0.0")
                .contains("2.0.0");
    }

    private void publishPom(String group, String artifact, String version, String depsXml) throws IOException {
        File dir = new File(repoDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        assertThat(dir.mkdirs()).isTrue();
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                    %s
                </project>
                """.formatted(group, artifact, version, depsXml);
        writeFile(new File(dir, artifact + "-" + version + ".pom"), pom);
    }

    private void writeFile(File destination, String content) throws IOException {
        Files.write(destination.toPath(), content.getBytes());
    }
}
