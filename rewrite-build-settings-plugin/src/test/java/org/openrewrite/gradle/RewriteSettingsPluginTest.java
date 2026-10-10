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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

class RewriteSettingsPluginTest {

    private static final String MIRROR = "https://artifactory.moderne.ninja/artifactory/moderne-cache-3/";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

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
    void routesPluginResolutionThroughArtifactoryWithCredentials(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, """
                gradlePluginPortal()
                        mavenCentral()
                """);

        assertThat(urls(run(projectDir, withArtifactoryCredentials()), "plugin"))
                .containsExactly(MIRROR, MIRROR, RewriteSettingsPlugin.CGP_URL);
    }

    @Test
    void declaresThePluginPortalInOrderToRouteIt(@TempDir File projectDir) throws IOException {
        // Without Code Genome Project credentials, nothing else has declared what an empty handler falls back to
        writeProject(projectDir, "");

        BuildResult result = run(projectDir, asList("printPluginRepositories",
                "-PcodegenomeUsername=",
                "-PcodegenomePassword=",
                "-PartifactoryUsername=test-user",
                "-PartifactoryPassword=test-token"));

        assertThat(result.getOutput()).contains("pluginRepo:Gradle Central Plugin Repository");
        assertThat(urls(result, "plugin")).containsExactly(MIRROR);
    }

    @Test
    void routesEveryProjectAndItsBuildScriptThroughArtifactory(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, "gradlePluginPortal()", "mavenCentral()", """
                buildscript {
                    repositories {
                        gradlePluginPortal()
                    }
                }

                repositories {
                    mavenCentral()
                    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
                }
                """);

        BuildResult result = run(projectDir, withArtifactoryCredentials());

        assertThat(urls(result, "shared")).containsExactly(MIRROR);
        assertThat(urls(result, "buildscript")).containsExactly(MIRROR);
        assertThat(urls(result, "project")).containsExactly(MIRROR, "https://repo.gradle.org/gradle/libs-releases/");
    }

    @Test
    void leavesRepositoriesWhereTheyWereDeclaredWithoutArtifactoryCredentials(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, "gradlePluginPortal()", "mavenCentral()", "repositories { mavenCentral() }");

        BuildResult result = run(projectDir, withCredentials(), environmentWithoutMirror());

        assertThat(urls(result, "plugin")).containsExactly("https://plugins.gradle.org/m2", RewriteSettingsPlugin.CGP_URL);
        assertThat(urls(result, "shared")).containsExactly(MAVEN_CENTRAL);
        assertThat(urls(result, "project")).containsExactly(MAVEN_CENTRAL);
    }

    @Test
    void takesTheMirrorCiExportsWhenThereAreNoGradleProperties(@TempDir File projectDir) throws IOException {
        writeProject(projectDir, "gradlePluginPortal()", "", "repositories { mavenCentral() }");
        Map<String, String> env = environmentWithoutMirror();
        env.put("REWRITE_GRADLE_MIRROR_URL", "https://mirror.example.com/maven/");
        env.put("REWRITE_GRADLE_MIRROR_USERNAME", "ci-user");
        env.put("REWRITE_GRADLE_MIRROR_PASSWORD", "ci-token");

        BuildResult result = run(projectDir, withCredentials(), env);

        assertThat(urls(result, "plugin")).containsExactly("https://mirror.example.com/maven/", RewriteSettingsPlugin.CGP_URL);
        assertThat(urls(result, "project")).containsExactly("https://mirror.example.com/maven/");
    }

    @Test
    void leavesRepositoriesWhereTheyWereDeclaredWhenCiHasNoSecretsToExport(@TempDir File projectDir) throws IOException {
        // A fork's pull request: the workflow still sets the variables, to secrets that come out empty
        writeProject(projectDir, "gradlePluginPortal()", "", "repositories { mavenCentral() }");
        Map<String, String> env = environmentWithoutMirror();
        env.put("REWRITE_GRADLE_MIRROR_URL", MIRROR);
        env.put("REWRITE_GRADLE_MIRROR_USERNAME", "");
        env.put("REWRITE_GRADLE_MIRROR_PASSWORD", "");

        BuildResult result = run(projectDir, withCredentials(), env);

        assertThat(urls(result, "plugin")).containsExactly("https://plugins.gradle.org/m2", RewriteSettingsPlugin.CGP_URL);
        assertThat(urls(result, "project")).containsExactly(MAVEN_CENTRAL);
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

    private static List<String> withArtifactoryCredentials() {
        return asList("printPluginRepositories",
                "-PcodegenomeUsername=test-user",
                "-PcodegenomePassword=cgp_test-token",
                "-PartifactoryUsername=test-user",
                "-PartifactoryPassword=test-token");
    }

    private static List<String> pluginRepositories(File projectDir, List<String> arguments) {
        return run(projectDir, arguments).getOutput().lines()
                .filter(line -> line.startsWith("pluginRepo:"))
                .map(line -> line.substring("pluginRepo:".length()))
                .toList();
    }

    /** Where the repositories of one kind point: {@code plugin}, {@code shared}, {@code buildscript} or {@code project}. */
    private static List<String> urls(BuildResult result, String kind) {
        String prefix = kind + "RepoUrl:";
        return result.getOutput().lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .toList();
    }

    private static BuildResult run(File projectDir, List<String> arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private static BuildResult run(File projectDir, List<String> arguments, Map<String, String> environment) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(arguments)
                .withEnvironment(environment)
                .build();
    }

    /** Whatever this build was itself given, which would otherwise decide the outcome in the test's place. */
    private static Map<String, String> environmentWithoutMirror() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("REWRITE_GRADLE_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_artifactory"));
        return env;
    }

    private static void writeProject(File projectDir, String repositories) throws IOException {
        writeProject(projectDir, repositories, "", "");
    }

    private static void writeProject(File projectDir, String repositories, String sharedRepositories, String buildScript) throws IOException {
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

                dependencyResolutionManagement {
                    repositories {
                        %s
                    }
                }

                fun url(repository: ArtifactRepository) = (repository as MavenArtifactRepository).url

                gradle.settingsEvaluated {
                    pluginManagement.repositories.forEach { println("pluginRepo:" + it.name) }
                    pluginManagement.repositories.forEach { println("pluginRepoUrl:" + url(it)) }
                    dependencyResolutionManagement.repositories.forEach { println("sharedRepoUrl:" + url(it)) }
                }
                gradle.projectsEvaluated {
                    rootProject.buildscript.repositories.forEach { println("buildscriptRepoUrl:" + url(it)) }
                    rootProject.repositories.forEach { println("projectRepoUrl:" + url(it)) }
                }
                """.formatted(repositories, sharedRepositories));
        //language=kotlin
        write(new File(projectDir, "build.gradle.kts"), """
                %s
                tasks.register("printPluginRepositories")
                """.formatted(buildScript));
    }

    private static void write(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
