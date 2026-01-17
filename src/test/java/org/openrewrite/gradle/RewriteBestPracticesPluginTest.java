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
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteBestPracticesPluginTest {
    @TempDir
    File testProjectDir;

    private File settingsFile;
    private File buildFile;

    //language=groovy
    private static final String SETTINGS_WITH_PLUGIN_MANAGEMENT = """
            pluginManagement {
                repositories {
                    mavenLocal()
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = 'test-project'
            """;

    @BeforeEach
    void setup() {
        settingsFile = new File(testProjectDir, "settings.gradle");
        buildFile = new File(testProjectDir, "build.gradle");
    }

    @Test
    void appliesPluginAndConfiguresRewriteTasks() throws Exception {
        Files.writeString(settingsFile.toPath(), SETTINGS_WITH_PLUGIN_MANAGEMENT);
        //language=groovy
        Files.writeString(buildFile.toPath(), """
                plugins {
                    id 'java'
                    id 'org.openrewrite.build.best-practices'
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments("tasks", "--all")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        String output = result.getOutput();
        assertThat(output).contains("rewriteRun");
        assertThat(output).contains("rewriteDryRun");
    }

    @Test
    void configuresActiveRecipe() throws Exception {
        Files.writeString(settingsFile.toPath(), SETTINGS_WITH_PLUGIN_MANAGEMENT);
        //language=groovy
        Files.writeString(buildFile.toPath(), """
                plugins {
                    id 'java'
                    id 'org.openrewrite.build.best-practices'
                }

                tasks.register('printActiveRecipes') {
                    doLast {
                        def rewriteExt = project.extensions.findByName('rewrite')
                        println "Active recipes: " + rewriteExt.activeRecipes
                    }
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments("printActiveRecipes")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(result.getOutput())
                .contains("org.openrewrite.recipes.rewrite.OpenRewriteRecipeBestPractices");
    }

    @Test
    void configuresRewriteDependency() throws Exception {
        Files.writeString(settingsFile.toPath(), SETTINGS_WITH_PLUGIN_MANAGEMENT);
        //language=groovy
        Files.writeString(buildFile.toPath(), """
                plugins {
                    id 'java'
                    id 'org.openrewrite.build.best-practices'
                }

                tasks.register('printRewriteDependencies') {
                    doLast {
                        configurations.getByName('rewrite').dependencies.each {
                            println "Dependency: ${it.group}:${it.name}:${it.version}"
                        }
                    }
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments("printRewriteDependencies")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(result.getOutput())
                .contains("org.openrewrite.recipe:rewrite-rewrite:");
    }

    @Test
    void configuresRepositories() throws Exception {
        Files.writeString(settingsFile.toPath(), SETTINGS_WITH_PLUGIN_MANAGEMENT);
        //language=groovy
        Files.writeString(buildFile.toPath(), """
                plugins {
                    id 'java'
                    id 'org.openrewrite.build.best-practices'
                }

                tasks.register('printRepositories') {
                    doLast {
                        repositories.each {
                            println "Repository: ${it.name}"
                        }
                    }
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments("printRepositories")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        String output = result.getOutput();
        assertThat(output).contains("MavenLocal");
        assertThat(output).contains("MavenRepo");
    }
}
