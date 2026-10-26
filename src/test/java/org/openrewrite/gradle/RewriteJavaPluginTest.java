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
import org.gradle.testretry.TestRetryPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE;
import static org.gradle.testkit.runner.TaskOutcome.NO_SOURCE;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

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
    void noTestSources() throws Exception {
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
    void flakyTestPassesOnItsRetryOnCi() throws Exception {
        writeTestProject();
        writeTest("FlakyTest", FLAKY_TEST);

        BuildResult result = testRunner(true).build();

        assertThat(requireNonNull(result.task(":test")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(testcases("FlakyTest")).containsExactly("failsTheFirstTime() failed", "failsTheFirstTime() passed");
    }

    @Test
    void flakyTestFailsTheBuildOffCi() throws Exception {
        writeTestProject();
        writeTest("FlakyTest", FLAKY_TEST);

        BuildResult result = testRunner(false).buildAndFail();

        assertThat(requireNonNull(result.task(":test")).getOutcome()).isEqualTo(FAILED);
        assertThat(testcases("FlakyTest")).containsExactly("failsTheFirstTime() failed");
    }

    @Test
    void brokenTestFailsTheBuildAfterItsRetry() throws Exception {
        writeTestProject();
        writeTest("BrokenTest",
          //language=java
          """
            package org.openrewrite.sample;

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.fail;

            class BrokenTest {
                @Test
                void alwaysFails() {
                    fail("broken");
                }
            }
            """);

        BuildResult result = testRunner(true).buildAndFail();

        assertThat(requireNonNull(result.task(":test")).getOutcome()).isEqualTo(FAILED);
        assertThat(testcases("BrokenTest")).containsExactly("alwaysFails() failed", "alwaysFails() failed");
    }

    @Test
    void tenFailedTestsAreNotRetried() throws Exception {
        writeTestProject();
        writeTest("BreakTest",
          //language=java
          """
            package org.openrewrite.sample;

            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;

            import static org.junit.jupiter.api.Assertions.fail;

            class BreakTest {
                @ParameterizedTest
                @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
                void broken(int n) {
                    fail("broken " + n);
                }
            }
            """);

        testRunner(true).buildAndFail();

        assertThat(testcases("BreakTest")).hasSize(10).allMatch(testcase -> testcase.endsWith(" failed"));
    }

    @Test
    void retrySettingsOfAnInitScriptsOwnCopyOfThePluginStand() throws Exception {
        writeTestProject();
        writeTest("FailsTwiceTest",
          //language=java
          """
            package org.openrewrite.sample;

            import org.junit.jupiter.api.Test;

            import java.io.File;

            import static org.junit.jupiter.api.Assertions.fail;

            class FailsTwiceTest {
                @Test
                void failsTwice() throws Exception {
                    if (new File("build/first-attempt").createNewFile() || new File("build/second-attempt").createNewFile()) {
                        fail("fails the first two times it runs");
                    }
                }
            }
            """);
        // On a classpath of its own, as an init script beside CGP's lock script would have it
        String testRetryJar = Path.of(TestRetryPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          .toString().replace('\\', '/');
        File initScript = new File(testProjectDir, "retry.init.gradle");
        Files.writeString(initScript.toPath(),
          //language=gradle
          """
            initscript {
                dependencies {
                    classpath files('%s')
                }
            }
            allprojects {
                apply plugin: org.gradle.testretry.TestRetryPlugin
                tasks.withType(Test).configureEach {
                    retry {
                        maxRetries = 2
                    }
                }
            }
            """.formatted(testRetryJar));

        BuildResult result = testRunner(true).withArguments("test", "-I", initScript.getPath()).build();

        assertThat(requireNonNull(result.task(":test")).getOutcome()).isEqualTo(SUCCESS);
        assertThat(testcases("FailsTwiceTest"))
          .containsExactly("failsTwice() failed", "failsTwice() failed", "failsTwice() passed");
    }

    @Test
    void ciAndDevelopersShareTestResultsInTheBuildCache() throws Exception {
        writeTestProject();
        Files.writeString(settingsFile.toPath(),
          //language=gradle
          """
            rootProject.name = 'retry'
            buildCache {
                local {
                    directory = new File(rootDir, 'build-cache')
                }
            }
            """);
        writeTest("PassingTest",
          //language=java
          """
            package org.openrewrite.sample;

            import org.junit.jupiter.api.Test;

            class PassingTest {
                @Test
                void passes() {
                }
            }
            """);

        BuildResult onCi = testRunner(true).withArguments("test", "--build-cache").build();
        assertThat(requireNonNull(onCi.task(":test")).getOutcome()).isEqualTo(SUCCESS);

        deleteRecursively(new File(testProjectDir, "build"));
        BuildResult offCi = testRunner(false).withArguments("test", "--build-cache").build();
        assertThat(requireNonNull(offCi.task(":test")).getOutcome()).isEqualTo(FROM_CACHE);
    }

    //language=java
    private static final String FLAKY_TEST = """
      package org.openrewrite.sample;

      import org.junit.jupiter.api.Test;

      import java.io.File;

      import static org.junit.jupiter.api.Assertions.fail;

      class FlakyTest {
          @Test
          void failsTheFirstTime() throws Exception {
              // Each attempt runs in a new test JVM, so a file remembers the first
              if (new File("build/first-attempt").createNewFile()) {
                  fail("fails the first time it runs");
              }
          }
      }
      """;

    private void writeTestProject() throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'retry'");
        Files.writeString(buildFile.toPath(),
          //language=gradle
          """
            plugins {
                id 'org.openrewrite.build.language-library'
            }
            """);
    }

    private void writeTest(String className, String source) throws IOException {
        Path test = testProjectDir.toPath().resolve("src/test/java/org/openrewrite/sample/" + className + ".java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, source);
    }

    /** A run of the project's tests, as CI or as a developer's machine runs them, whichever this test runs on. */
    private GradleRunner testRunner(boolean ci) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (ci) {
            env.put("CI", "true");
        } else {
            env.remove("CI");
        }
        return GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("test")
          .withEnvironment(env)
          .withPluginClasspath();
    }

    /** Each testcase of a test class's JUnit XML report, in the order Gradle wrote them, and whether it failed. */
    private List<String> testcases(String className) throws Exception {
        File report = new File(testProjectDir, "build/test-results/test/TEST-org.openrewrite.sample." + className + ".xml");
        NodeList testcases = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report)
          .getElementsByTagName("testcase");
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < testcases.getLength(); i++) {
            Element testcase = (Element) testcases.item(i);
            outcomes.add(testcase.getAttribute("name") +
                         (testcase.getElementsByTagName("failure").getLength() > 0 ? " failed" : " passed"));
        }
        return outcomes;
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(file.toPath());
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
          .contains("MAVEN_CENTRAL_MIRROR_URL=https://artifactory.moderne.ninja/artifactory/moderne-cache-3/")
          .contains("MAVEN_CENTRAL_MIRROR_USERNAME=test-user")
          .contains("MAVEN_CENTRAL_MIRROR_PASSWORD=test-token");
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
          .doesNotContain("MAVEN_CENTRAL_MIRROR_");
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
                def mirror = tasks.named('test', Test).get().environment.findAll { it.key.startsWith('MAVEN_CENTRAL_MIRROR_') }
                doLast {
                    mirror.each { name, value -> println "$name=$value" }
                }
            }
            """);
    }

    /** CI exports these itself, which would otherwise show up whether or not the plugin set them. */
    private static Map<String, String> environmentWithoutMirror() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("MAVEN_CENTRAL_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_artifactory"));
        return env;
    }
}
