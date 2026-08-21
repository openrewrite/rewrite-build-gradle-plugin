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
import java.nio.file.Files;
import java.nio.file.Path;
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
}
