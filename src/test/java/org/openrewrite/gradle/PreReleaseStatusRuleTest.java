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

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PreReleaseStatusRuleTest {
    @TempDir
    File testProjectDir;

    @ParameterizedTest
    @CsvSource({
      "1.1.0-M1, 1.0.0",
      "1.1.0.M1, 1.0.0",
      "1.1.0-RC1, 1.0.0",
      "1.1.0-rc.2, 1.0.0",
      "1.1.0-rc-1, 1.0.0",
      "1.1.0, 1.1.0",
      "1.1.0.RELEASE, 1.1.0.RELEASE"
    })
    void latestReleaseSkipsMilestonesAndReleaseCandidates(String newest, String expected) throws IOException {
        publish("1.0.0", newest);
        Files.writeString(new File(testProjectDir, "settings.gradle").toPath(), "rootProject.name = 'prerelease'");
        Files.writeString(new File(testProjectDir, "build.gradle").toPath(),
          //language=gradle
          """
            plugins {
                id 'org.openrewrite.build.java-base'
            }

            repositories {
                maven { url = uri('repo') }
            }

            configurations {
                probe
            }

            dependencies {
                probe 'com.example:lib:latest.release'
            }

            tasks.register('printResolved') {
                def resolved = configurations.probe.incoming.resolutionResult.rootComponent
                doLast {
                    resolved.get().dependencies.each { println "RESOLVED=${it.selected.moduleVersion.version}" }
                }
            }
            """);

        String output = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("printResolved")
          .withPluginClasspath()
          .build()
          .getOutput();

        assertThat(output).contains("RESOLVED=" + expected);
    }

    private void publish(String... versions) throws IOException {
        Path module = testProjectDir.toPath().resolve("repo/com/example/lib");
        StringBuilder listed = new StringBuilder();
        for (String version : versions) {
            Path dir = module.resolve(version);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("lib-" + version + ".pom"),
              //language=xml
              """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>%s</version>
                  <packaging>pom</packaging>
                </project>
                """.formatted(version));
            listed.append("<version>").append(version).append("</version>");
        }
        Files.writeString(module.resolve("maven-metadata.xml"),
          //language=xml
          """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>lib</artifactId>
              <versioning><versions>%s</versions></versioning>
            </metadata>
            """.formatted(listed));
    }
}
