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
import java.util.Arrays;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class RewriteRecipeLibraryPluginTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;

    @BeforeEach
    void setup() {
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
                .withArguments("downloadRecipeDependencies", "--stacktrace", "--warning-mode=fail")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(requireNonNull(result.task(":downloadRecipeDependencies")).getOutcome()).isEqualTo(SUCCESS);
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
                .withArguments("downloadRecipeDependencies", "--stacktrace", "--warning-mode=fail")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(requireNonNull(result.task(":downloadRecipeDependencies")).getOutcome()).isEqualTo(SUCCESS);

        assertThat(cp.list()).containsExactlyInAnyOrder("guava-30.1-jre.jar", "guava-31.1-jre.jar");
    }

    @Test
    void addsRewriteJava25ParserToTestsAlignedByRewriteBom() throws Exception {
        assertThat(testRuntimeDependencies("implementation platform('org.openrewrite:rewrite-bom:8.0.0')"))
          .contains("org.openrewrite:rewrite-java-25");
    }

    @Test
    void noRewriteJava25ParserWithoutRewriteBom() throws Exception {
        assertThat(testRuntimeDependencies("implementation 'org.openrewrite:rewrite-yaml:8.0.0'"))
          .doesNotContain("org.openrewrite:rewrite-java-25");
    }

    @Test
    void handsTestsTheToolingPluginAtTheModelsVersion() throws Exception {
        File repository = new File(projectDir, "repository");
        publish(repository, "model", "1.0.0", "");
        publish(repository, "plugin", "1.0.0", "model");
        publish(repository, "plugin", "1.1.0", "model");
        writeFile(settingsFile, "rootProject.name = 'my-project'");
        //language=groovy
        writeFile(buildFile, """
                plugins {
                    id 'org.openrewrite.build.recipe-library-base'
                }
                repositories {
                    maven { url = file('repository') }
                }
                dependencies {
                    testImplementation 'org.openrewrite.gradle.tooling:model:1.0.0'
                }
                tasks.register('printToolingPluginClasspath') {
                    doLast {
                        def property = '-Dorg.openrewrite.gradle.local.use-embedded-classpath='
                        def manifest = tasks.test.allJvmArgs.find { it.startsWith(property) }.substring(property.length())
                        new File(manifest).eachLine { println "TOOLING_PLUGIN_CLASSPATH=${new File(it).name}" }
                    }
                }
                """);

        String output = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("printToolingPluginClasspath", "--stacktrace")
                .withPluginClasspath()
                .build()
                .getOutput();

        assertThat(output.lines().filter(line -> line.startsWith("TOOLING_PLUGIN_CLASSPATH=")))
          .containsExactlyInAnyOrder("TOOLING_PLUGIN_CLASSPATH=plugin-1.0.0.jar", "TOOLING_PLUGIN_CLASSPATH=model-1.0.0.jar");
    }

    private static void publish(File repository, String artifactId, String version, String dependsOn) throws IOException {
        File artifact = new File(repository, "org/openrewrite/gradle/tooling/" + artifactId);
        File directory = new File(artifact, version);
        assertThat(directory.mkdirs()).isTrue();
        String versions = Arrays.stream(requireNonNull(artifact.listFiles(File::isDirectory)))
          .map(published -> "<version>" + published.getName() + "</version>")
          .collect(joining());
        Files.writeString(new File(artifact, "maven-metadata.xml").toPath(), """
                <metadata>
                  <groupId>org.openrewrite.gradle.tooling</groupId><artifactId>%s</artifactId>
                  <versioning><versions>%s</versions></versioning>
                </metadata>""".formatted(artifactId, versions));
        String dependencies = dependsOn.isEmpty() ? "" : """
                <dependencies><dependency>
                  <groupId>org.openrewrite.gradle.tooling</groupId><artifactId>%s</artifactId><version>%s</version>
                </dependency></dependencies>""".formatted(dependsOn, version);
        Files.writeString(new File(directory, artifactId + "-" + version + ".pom").toPath(), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.openrewrite.gradle.tooling</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>""".formatted(artifactId, version, dependencies));
        Files.write(new File(directory, artifactId + "-" + version + ".jar").toPath(), new byte[0]);
    }

    private String testRuntimeDependencies(String dependency) throws IOException {
        writeFile(settingsFile, "rootProject.name = 'my-project'");
        //language=groovy
        writeFile(buildFile, """
                plugins {
                    id 'org.openrewrite.build.recipe-library-base'
                }
                dependencies {
                    %s
                }
                tasks.register('printTestRuntimeDependencies') {
                    def dependencies = configurations.testRuntimeClasspath.incoming.dependencies
                    doLast {
                        dependencies.each { println "TEST_RUNTIME=${it.group}:${it.name}" }
                    }
                }
                """.formatted(dependency));

        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("printTestRuntimeDependencies", "--stacktrace")
                .withPluginClasspath()
                .build()
                .getOutput();
    }

    private void writeFile(File destination, String content) throws IOException {
        Files.write(destination.toPath(), content.getBytes());
    }
}
