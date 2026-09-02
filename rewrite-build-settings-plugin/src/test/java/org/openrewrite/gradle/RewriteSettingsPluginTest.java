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
import java.util.List;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

class RewriteSettingsPluginTest {

    @Test
    void registersCodegenomeWithCredentials(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, "gradlePluginPortal()");

        assertThat(pluginRepositories(projectDir, withCredentials()))
                .containsExactly("Gradle Central Plugin Repository", "codegenome");
    }

    @Test
    void keepsThePluginPortalWhenSettingsDeclaresNoRepositories(@TempDir File projectDir) throws IOException {
        // An empty handler is what makes Gradle fall back to the portal, so adding to one has to restore it
        writeProject(projectDir, "");

        assertThat(pluginRepositories(projectDir, withCredentials()))
                .containsExactly("Gradle Central Plugin Repository", "codegenome");
    }

    @Test
    void leavesACodegenomeSettingsAlreadyDeclaredAlone(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, """
                gradlePluginPortal()
                        maven {
                            name = "codegenome"
                            url = uri("https://artifacts.codegenomeproject.org/maven")
                        }
                """);

        assertThat(pluginRepositories(projectDir, withCredentials()))
                .containsExactly("Gradle Central Plugin Repository", "codegenome");
    }

    @Test
    void leavesRepositoriesAloneWithoutCredentials(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, "gradlePluginPortal()");

        // Empty -P values rather than an absent property: CI exports ORG_GRADLE_PROJECT_codegenome*,
        // and a command line property outranks it
        BuildResult result = run(projectDir, asList("printPluginRepositories",
                "-PcodegenomeUsername=",
                "-PcodegenomePassword="));

        assertThat(result.getOutput()).doesNotContain("pluginRepo:codegenome");
        assertThat(result.getOutput()).contains("Code Genome Project credentials are absent");
    }

    @Test
    void carriesNoOpenRewriteDependencies() {
        // The whole point: this plugin resolves off the plugin portal alone, so that the plugins which do
        // depend on org.openrewrite artifacts can then resolve them from the repository it adds
        assertThat(GradleRunner.create().withPluginClasspath().getPluginClasspath())
                .noneMatch(entry -> entry.getName().startsWith("rewrite-"));
    }

    @Test
    void idMatchesTheRegisteredPluginId() {
        assertThat(getClass().getClassLoader()
                .getResource("META-INF/gradle-plugins/" + RewriteSettingsPlugin.ID + ".properties"))
                .isNotNull();
    }

    private static List<String> withCredentials() {
        return asList("printPluginRepositories",
                "-PcodegenomeUsername=test-user",
                "-PcodegenomePassword=cgp_test-token");
    }

    private static List<String> pluginRepositories(File projectDir, List<String> arguments) {
        return run(projectDir, arguments).getOutput().lines()
                .filter(line -> line.startsWith("pluginRepo:"))
                .map(line -> line.substring("pluginRepo:".length()))
                .toList();
    }

    private static BuildResult run(File projectDir, List<String> arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private static void writeProject(File projectDir, String repositories) throws IOException {
        //language=kotlin
        write(new File(projectDir, "settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        %s
                    }
                }

                plugins {
                    id("org.openrewrite.build.settings")
                }

                rootProject.name = "settings-consumer"

                gradle.settingsEvaluated {
                    pluginManagement.repositories.forEach { println("pluginRepo:" + it.name) }
                }
                """.formatted(repositories));
        //language=kotlin
        write(new File(projectDir, "build.gradle.kts"), """
                tasks.register("printPluginRepositories")
                """);
    }

    private static void write(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
