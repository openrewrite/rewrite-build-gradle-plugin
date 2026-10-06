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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.NO_SOURCE;

class RewriteJavaPluginTest {
    @TempDir
    File testProjectDir;

    private File settingsFile;
    private File buildFile;

    @BeforeEach
    void setup() {
        settingsFile = new File(testProjectDir, "settings.gradle");
        buildFile = new File(testProjectDir, "build.gradle");
    }

    @Test
    void retry() throws Exception {
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

        assertThat(requireNonNull(result.task(":test")).getOutcome()).isEqualTo(NO_SOURCE);
    }

    @Test
    void jacksonVersionAppliedToConsumersMatchesOurOwn() throws Exception {
        Matcher ourJacksonBom = Pattern.compile("com\\.fasterxml\\.jackson:jackson-bom:([^\"]+)")
          .matcher(Files.readString(Path.of("build.gradle.kts")));
        assertThat(ourJacksonBom.find())
          .as("no jackson-bom platform found in build.gradle.kts")
          .isTrue();
        String jacksonVersion = ourJacksonBom.group(1);

        Files.writeString(settingsFile.toPath(), "rootProject.name = 'jackson-version'");
        Files.writeString(buildFile.toPath(),
          //language=gradle
          """
            plugins {
                id 'org.openrewrite.build.language-library'
            }

            tasks.register('printJacksonBom') {
                doLast {
                    configurations.api.allDependencies.each { d ->
                        if (d.group == 'com.fasterxml.jackson' && d.name == 'jackson-bom') {
                            println "JACKSON_BOM=${d.group}:${d.name}:${d.version}"
                        }
                    }
                }
            }
            """);

        assertThat(GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("printJacksonBom")
          .withPluginClasspath()
          .build()
          .getOutput())
          .as("bump the rewriteJava.jacksonVersion convention in RewriteJavaPlugin along with build.gradle.kts")
          .contains("JACKSON_BOM=com.fasterxml.jackson:jackson-bom:" + jacksonVersion);
    }

    @Test
    void defaultToolchainSelectsJunit6Bom() throws Exception {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'default-toolchain'");
        //language=gradle
        Files.writeString(buildFile.toPath(),
          //language=gradle
          """
            plugins {
                id 'org.openrewrite.build.language-library'
            }

            tasks.register('printJunitBom') {
                doLast {
                    configurations.testCompileClasspath.allDependencies.each { d ->
                        if (d.group == 'org.junit' && d.name == 'junit-bom') {
                            println "JUNIT_BOM=${d.group}:${d.name}:${d.version}"
                        }
                    }
                }
            }
            """);

        assertThat(GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("printJunitBom")
          .withPluginClasspath()
          .build()
          .getOutput()).contains("JUNIT_BOM=org.junit:junit-bom:6.+");
    }

    @Test
    void artifactoryCredentialsRouteTestsThroughMirror() throws Exception {
        writeMirrorProbe();

        assertThat(GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("printMirror", "-PartifactoryUsername=test-user", "-PartifactoryPassword=test-token")
          .withEnvironment(environmentWithoutMirror())
          .withPluginClasspath()
          .build()
          .getOutput())
          .contains("REWRITE_GRADLE_MIRROR_URL=https://artifactory.moderne.ninja/artifactory/moderne-cache-3/")
          .contains("REWRITE_GRADLE_MIRROR_USERNAME=test-user")
          .contains("REWRITE_GRADLE_MIRROR_PASSWORD=test-token");
    }

    @Test
    void noMirrorWithoutArtifactoryCredentials() throws Exception {
        writeMirrorProbe();

        assertThat(GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("printMirror")
          .withEnvironment(environmentWithoutMirror())
          .withPluginClasspath()
          .build()
          .getOutput())
          .doesNotContain("REWRITE_GRADLE_MIRROR_");
    }

    private void writeMirrorProbe() throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'mirror'");
        Files.writeString(buildFile.toPath(),
          //language=gradle
          """
            plugins {
                id 'org.openrewrite.build.language-library'
            }

            tasks.register('printMirror') {
                def mirror = tasks.named('test', Test).get().environment.findAll { it.key.startsWith('REWRITE_GRADLE_MIRROR_') }
                doLast {
                    mirror.each { name, value -> println "$name=$value" }
                }
            }
            """);
    }

    /** CI exports these itself, which would otherwise show up whether or not the plugin set them. */
    private static Map<String, String> environmentWithoutMirror() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("REWRITE_GRADLE_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_artifactory"));
        return env;
    }
}
