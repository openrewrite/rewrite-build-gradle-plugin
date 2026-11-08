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

class RewriteMavenCentralMirrorPluginTest {

    private static final String CGP_MAVEN_CACHE = "https://artifacts.codegenomeproject.org/maven-public/";
    private static final String CGP_PLUGIN_PORTAL = "https://artifacts.codegenomeproject.org/gradle-plugins/";
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
        Project project = projectWithCodeGenomeProjectCredentials(projectDir);
        // What a few of the plugins expect to find already applied
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("maven-publish");

        project.getPluginManager().apply(pluginId);

        // The mirror CI names when this build runs on one, which no test can take out of its own environment, and
        // CGP's Maven cache otherwise
        MavenCentralMirror mirror = requireNonNull(MavenCentralMirror.ifAvailable(project.getProviders()));
        assertThat(mirror.serves(project.getRepositories().mavenCentral())).isTrue();
    }

    /** Every plugin this build registers, so that one added later is held to the same rule. */
    static Stream<String> pluginIds() throws URISyntaxException {
        URL descriptor = requireNonNull(RewriteMavenCentralMirrorPluginTest.class.getClassLoader()
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
          "-PcodegenomeUsername=test-user", "-PcodegenomePassword=cgp_test-token")
          .withEnvironment(environmentWithoutMirror()));

        assertThat(repositories).hasSize(5);
        assertThat(repositories.getFirst()).startsWith("MavenLocal=file:");
        assertThat(repositories.subList(1, 5)).containsExactly(
          "MavenRepo=" + CGP_MAVEN_CACHE,
          // Whose version lists are the portal's own, which maven-public's are not for a plugin Central lists too
          "Gradle Central Plugin Repository=" + CGP_PLUGIN_PORTAL,
          "byItsOtherName=" + CGP_MAVEN_CACHE,
          "elsewhere=https://repo.gradle.org/gradle/libs-releases/");
    }

    @Test
    void pointsThePluginPortalAtThePortalsOwnRepositoryBesideAMavenPublicCiNames(@TempDir File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'mirrored'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
          plugins {
              id 'org.openrewrite.build.metadata'
          }
          repositories {
              gradlePluginPortal()
              mavenCentral()
          }
          """ + PRINT_REPOSITORIES);
        Map<String, String> env = environmentWithoutMirror();
        env.put("MAVEN_CENTRAL_MIRROR_URL", "https://mirror.example.com/maven-public/");
        env.put("MAVEN_CENTRAL_MIRROR_USERNAME", "ci-user");
        env.put("MAVEN_CENTRAL_MIRROR_PASSWORD", "ci-token");

        assertThat(repositories(runner(projectDir, "printRepositories").withEnvironment(env))).containsExactly(
          "Gradle Central Plugin Repository=https://mirror.example.com/gradle-plugins/",
          "MavenRepo=https://mirror.example.com/maven-public/");
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
          "-PcodegenomeUsername=test-user", "-PcodegenomePassword=cgp_test-token")
          .withEnvironment(environmentWithoutMirror())))
          .containsExactly("MavenRepo=" + CGP_MAVEN_CACHE);
    }

    @Test
    void readsCodeGenomeProjectCredentialsFromTheEnvironment(@TempDir File projectDir) throws IOException {
        // As devpods and CI set them, which the recipe repositories' settings read for pluginManagement too
        writeMavenCentralConsumer(projectDir);
        Map<String, String> env = environmentWithoutMirror();
        env.put("CODEGENOME_USERNAME", "env-user");
        env.put("CODEGENOME_TOKEN", "cgp_env-token");

        assertThat(repositories(runner(projectDir, "printRepositories").withEnvironment(env)))
          .containsExactly("MavenRepo=" + CGP_MAVEN_CACHE);
    }

    @Test
    void leavesRepositoriesAloneWithoutCredentials(@TempDir File projectDir) throws IOException {
        writeMavenCentralConsumer(projectDir);

        assertThat(repositories(runner(projectDir, "printRepositories")
          .withEnvironment(environmentWithoutMirror())))
          .containsExactly("MavenRepo=" + MAVEN_CENTRAL);
    }

    @Test
    void noLongerReadsArtifactoryCredentials(@TempDir File projectDir) throws IOException {
        writeMavenCentralConsumer(projectDir);

        assertThat(repositories(runner(projectDir, "printRepositories",
          "-PartifactoryUsername=test-user", "-PartifactoryPassword=test-token")
          .withEnvironment(environmentWithoutMirror())))
          .containsExactly("MavenRepo=" + MAVEN_CENTRAL);
    }

    @Test
    void takesTheMirrorCiExports(@TempDir File projectDir) throws IOException {
        writeMavenCentralConsumer(projectDir);
        Map<String, String> env = environmentWithoutMirror();
        env.put("MAVEN_CENTRAL_MIRROR_URL", "https://mirror.example.com/maven/");
        env.put("MAVEN_CENTRAL_MIRROR_USERNAME", "ci-user");
        env.put("MAVEN_CENTRAL_MIRROR_PASSWORD", "ci-token");

        assertThat(repositories(runner(projectDir, "printRepositories").withEnvironment(env)))
          .containsExactly("MavenRepo=https://mirror.example.com/maven/");
    }

    @Test
    void prefersTheMirrorCiExportsToCodeGenomeProjectCredentials(@TempDir File projectDir) throws IOException {
        // A CI whose builds read with a credential of their own, while the Code Genome Project's is a person's
        writeMavenCentralConsumer(projectDir);
        Map<String, String> env = environmentWithoutMirror();
        env.put("MAVEN_CENTRAL_MIRROR_URL", "https://mirror.example.com/maven/");
        env.put("MAVEN_CENTRAL_MIRROR_USERNAME", "ci-user");
        env.put("MAVEN_CENTRAL_MIRROR_PASSWORD", "ci-token");

        assertThat(repositories(runner(projectDir, "printRepositories",
          "-PcodegenomeUsername=test-user", "-PcodegenomePassword=cgp_test-token").withEnvironment(env)))
          .containsExactly("MavenRepo=https://mirror.example.com/maven/");
    }

    @Test
    void leavesRepositoriesAloneWhenCiHasNoSecretsToExport(@TempDir File projectDir) throws IOException {
        // A fork's pull request: the workflow still sets the variables, to secrets that come out empty
        writeMavenCentralConsumer(projectDir);
        Map<String, String> env = environmentWithoutMirror();
        env.put("MAVEN_CENTRAL_MIRROR_URL", CGP_MAVEN_CACHE);
        env.put("MAVEN_CENTRAL_MIRROR_USERNAME", "");
        env.put("MAVEN_CENTRAL_MIRROR_PASSWORD", "");
        env.put("CODEGENOME_USERNAME", "");
        env.put("CODEGENOME_TOKEN", "");

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
    private static Project projectWithCodeGenomeProjectCredentials(File projectDir) {
        String usernameProperty = "org.gradle.project.codegenomeUsername";
        String passwordProperty = "org.gradle.project.codegenomePassword";
        System.setProperty(usernameProperty, "test-user");
        System.setProperty(passwordProperty, "cgp_test-token");
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

    /**
     * Whatever this build was itself given, a mirror or Code Genome Project credentials, which would otherwise decide
     * the outcome in the test's place.
     */
    static Map<String, String> environmentWithoutMirror() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("MAVEN_CENTRAL_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_codegenome") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_codeGenome") ||
                                      "CODEGENOME_USERNAME".equals(name) ||
                                      "CODEGENOME_TOKEN".equals(name));
        return env;
    }

    private static void writeFile(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
