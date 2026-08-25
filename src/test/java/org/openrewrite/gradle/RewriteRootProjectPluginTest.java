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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteRootProjectPluginTest {
    @TempDir
    File projectDir;

    @Test
    void recipeLibrariesDoNotPublishToMavenCentral() throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'my-project'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'org.openrewrite.build.recipe-library'
                }
                group = 'org.openrewrite'
                version = '1.0'
                """);

        assertThat(publishDryRun()).doesNotContain("SonatypeRepository");
    }

    @Test
    void languageLibrariesDoNotPublishToMavenCentral() throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), """
                rootProject.name = 'my-project'
                include 'lang'
                """);
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'org.openrewrite.build.root'
                }
                allprojects {
                    group = 'org.openrewrite'
                    version = '1.0'
                }
                """);
        assertThat(new File(projectDir, "lang").mkdirs()).isTrue();
        //language=groovy
        writeFile(new File(projectDir, "lang/build.gradle"), """
                plugins {
                    id 'org.openrewrite.build.language-library'
                }
                """);

        String output = publishDryRun();
        assertThat(output).contains(":lang:publish");
        assertThat(output).doesNotContain("SonatypeRepository");
    }

    /**
     * The shared release workflow runs this alongside {@code publish}; it has to keep resolving now
     * that the Nexus plugin no longer contributes it.
     */
    @Test
    void closeAndReleaseSonatypeStagingRepositoryRemainsANoOp() throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'my-project'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'org.openrewrite.build.recipe-library'
                }
                group = 'org.openrewrite'
                version = '1.0'
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("closeAndReleaseSonatypeStagingRepository")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput()).contains("BUILD SUCCESSFUL");
    }

    private String publishDryRun() {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("publish", "--dry-run")
                .withPluginClasspath()
                .build()
                .getOutput();
    }

    private static void writeFile(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
