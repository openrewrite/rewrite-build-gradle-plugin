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

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class RewriteArtifactoryMirrorPluginTest {

    private static final String MIRROR = "https://artifactory.moderne.ninja/artifactory/moderne-cache-3/";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    //language=groovy
    private static final String PRINT_REPOSITORIES = """
      tasks.register('printRepositories') {
          def repos = repositories.collect { "repo:${it.name}=${it.url}" }
          doLast { repos.each { println it } }
      }
      """;

    @ParameterizedTest
    @MethodSource("pluginIds")
    void everyPluginRoutesMavenCentralThroughTheMirror(String pluginId, @TempDir File projectDir) {
        Project project = projectWithArtifactoryCredentials(projectDir);
        // What a few of the plugins expect to find already applied
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("maven-publish");

        project.getPluginManager().apply(pluginId);

        assertThat(project.getRepositories().mavenCentral().getUrl()).hasToString(MIRROR);
    }

    /** Every plugin this build registers, so that one added later is held to the same rule. */
    static Stream<String> pluginIds() throws URISyntaxException {
        URL descriptor = requireNonNull(RewriteArtifactoryMirrorPluginTest.class.getClassLoader()
          .getResource("META-INF/gradle-plugins/" + RewriteDependencyRepositoriesPlugin.ID + ".properties"));
        return Arrays.stream(requireNonNull(new File(descriptor.toURI()).getParentFile().list()))
          .map(name -> name.substring(0, name.length() - ".properties".length()));
    }

    @Test
    void redirectsEveryRepositoryThatReachesMavenCentral(@TempDir File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'mirrored'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
          plugins {
              id 'org.openrewrite.build.java-base'
          }
          repositories {
              mavenLocal()
              mavenCentral()
              gradlePluginPortal()
              maven { name = 'byItsOtherName'; url = uri('https://repo1.maven.org/maven2') }
              maven { name = 'elsewhere'; url = uri('https://repo.gradle.org/gradle/libs-releases/') }
          }
          """ + PRINT_REPOSITORIES);

        List<String> repositories = repositories(runner(projectDir, "printRepositories",
          "-PartifactoryUsername=test-user", "-PartifactoryPassword=test-token"));

        assertThat(repositories).hasSize(5);
        assertThat(repositories.get(0)).startsWith("MavenLocal=file:");
        assertThat(repositories.subList(1, 5)).containsExactly(
          "MavenRepo=" + MIRROR,
          "Gradle Central Plugin Repository=" + MIRROR,
          "byItsOtherName=" + MIRROR,
          "elsewhere=https://repo.gradle.org/gradle/libs-releases/");
    }

    @Test
    void redirectsRepositoriesDeclaredBeforeThePluginWasApplied(@TempDir File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), """
          rootProject.name = 'mirrored'
          include 'sub'
          """);
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
          subprojects {
              repositories {
                  mavenCentral()
              }
          }
          """);
        assertThat(new File(projectDir, "sub").mkdirs()).isTrue();
        //language=groovy
        writeFile(new File(projectDir, "sub/build.gradle"), """
          plugins {
              id 'org.openrewrite.build.metadata'
          }
          """ + PRINT_REPOSITORIES);

        assertThat(repositories(runner(projectDir, ":sub:printRepositories",
          "-PartifactoryUsername=test-user", "-PartifactoryPassword=test-token")))
          .containsExactly("MavenRepo=" + MIRROR);
    }

    @Test
    void leavesRepositoriesAloneWithoutCredentials(@TempDir File projectDir) throws IOException {
        writeMavenCentralConsumer(projectDir);

        assertThat(repositories(runner(projectDir, "printRepositories")
          .withEnvironment(environmentWithoutMirror())))
          .containsExactly("MavenRepo=" + MAVEN_CENTRAL);
    }

    @Test
    void takesTheMirrorCiExportsWhenThereAreNoGradleProperties(@TempDir File projectDir) throws IOException {
        writeMavenCentralConsumer(projectDir);
        Map<String, String> env = environmentWithoutMirror();
        env.put("REWRITE_GRADLE_MIRROR_URL", "https://mirror.example.com/maven/");
        env.put("REWRITE_GRADLE_MIRROR_USERNAME", "ci-user");
        env.put("REWRITE_GRADLE_MIRROR_PASSWORD", "ci-token");

        assertThat(repositories(runner(projectDir, "printRepositories").withEnvironment(env)))
          .containsExactly("MavenRepo=https://mirror.example.com/maven/");
    }

    @Test
    void leavesRepositoriesAloneWhenCiHasNoSecretsToExport(@TempDir File projectDir) throws IOException {
        // A fork's pull request: the workflow still sets the variables, to secrets that come out empty
        writeMavenCentralConsumer(projectDir);
        Map<String, String> env = environmentWithoutMirror();
        env.put("REWRITE_GRADLE_MIRROR_URL", MIRROR);
        env.put("REWRITE_GRADLE_MIRROR_USERNAME", "");
        env.put("REWRITE_GRADLE_MIRROR_PASSWORD", "");

        assertThat(repositories(runner(projectDir, "printRepositories").withEnvironment(env)))
          .containsExactly("MavenRepo=" + MAVEN_CENTRAL);
    }

    @Test
    void resolvesThroughTheMirrorWithItsCredentials(@TempDir File projectDir, @TempDir File mirrorDir) throws IOException {
        try (FakeMirror mirror = new FakeMirror(mirrorDir)) {
            mirror.publish("com.example", "lib", "1.0");
            writeResolvingProject(projectDir, "mavenCentral()", "com.example:lib:1.0");

            assertThat(runner(projectDir, "resolve").withEnvironment(mirror.environment()).build().getOutput())
              .contains("resolved:lib-1.0.jar");
        }
    }

    @Test
    void servesOnlyTheReleasesMavenCentralWould(@TempDir File projectDir, @TempDir File mirrorDir) throws IOException {
        try (FakeMirror mirror = new FakeMirror(mirrorDir)) {
            mirror.publish("com.example", "lib", "1.0");
            mirror.publish("com.example", "lib", "2.0-SNAPSHOT");
            // Gradle already keeps mavenCentral() itself to releases, but not the plugin portal
            writeResolvingProject(projectDir, "gradlePluginPortal()", "com.example:lib:latest.integration");

            assertThat(runner(projectDir, "resolve").withEnvironment(mirror.environment()).build().getOutput())
              .contains("resolved:lib-1.0.jar")
              .doesNotContain("SNAPSHOT");
        }
    }

    @Test
    void keepsTheContentFiltersARepositoryWasDeclaredWith(@TempDir File projectDir, @TempDir File mirrorDir) throws IOException {
        try (FakeMirror mirror = new FakeMirror(mirrorDir)) {
            mirror.publish("com.example.excluded", "lib", "1.0");
            writeResolvingProject(projectDir,
              "mavenCentral { content { excludeGroup 'com.example.excluded' } }",
              "com.example.excluded:lib:1.0");

            assertThat(runner(projectDir, "resolve").withEnvironment(mirror.environment()).buildAndFail().getOutput())
              .contains("Could not find com.example.excluded:lib:1.0");
        }
    }

    /** System properties rather than {@code gradle.properties}, which {@code ORG_GRADLE_PROJECT_} environment variables outrank. */
    private static Project projectWithArtifactoryCredentials(File projectDir) {
        String usernameProperty = "org.gradle.project.artifactoryUsername";
        String passwordProperty = "org.gradle.project.artifactoryPassword";
        System.setProperty(usernameProperty, "test-user");
        System.setProperty(passwordProperty, "test-token");
        try {
            return ProjectBuilder.builder()
              .withProjectDir(projectDir)
              .withGradleUserHomeDir(new File(projectDir, "gradle-home"))
              .build();
        } finally {
            System.clearProperty(usernameProperty);
            System.clearProperty(passwordProperty);
        }
    }

    private static void writeMavenCentralConsumer(File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'mirrored'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
          plugins {
              id 'org.openrewrite.build.metadata'
          }
          repositories {
              mavenCentral()
          }
          """ + PRINT_REPOSITORIES);
    }

    private static void writeResolvingProject(File projectDir, String repository, String dependency) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'mirrored'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
          plugins {
              id 'org.openrewrite.build.metadata'
          }
          repositories {
              %s
          }
          configurations {
              probe
          }
          dependencies {
              probe '%s'
          }
          tasks.register('resolve') {
              def files = configurations.probe
              doLast { files.each { println "resolved:${it.name}" } }
          }
          """.formatted(repository, dependency));
    }

    private static GradleRunner runner(File projectDir, String... arguments) {
        return GradleRunner.create()
          .withProjectDir(projectDir)
          .withPluginClasspath()
          .withArguments(arguments);
    }

    private static List<String> repositories(GradleRunner runner) {
        BuildResult result = runner.build();
        return result.getOutput().lines()
          .filter(line -> line.startsWith("repo:"))
          .map(line -> line.substring("repo:".length()))
          .toList();
    }

    /** Whatever this build was itself given, which would otherwise decide the outcome in the test's place. */
    private static Map<String, String> environmentWithoutMirror() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("REWRITE_GRADLE_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_artifactory"));
        return env;
    }

    private static void writeFile(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
