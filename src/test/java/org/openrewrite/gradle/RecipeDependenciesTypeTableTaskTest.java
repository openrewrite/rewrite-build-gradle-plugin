/*
 * Copyright 2025 the original author or authors.
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
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.internal.parser.TypeTable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collection;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class RecipeDependenciesTypeTableTaskTest {
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
    void multipleVersionsOfGuava() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'org.openrewrite.build.recipe-library-base'
          }
          repositories {
              mavenCentral()
          }
          recipeDependencies {
              parserClasspath 'com.google.guava:guava:30.1-jre'
              parserClasspath 'com.google.guava:guava:31.1-jre'
          }
          """);

        BuildResult result = runTypeTableTaskAndSucceed();
        assertThat(requireNonNull(result.task(":createTypeTable")).getOutcome()).isEqualTo(SUCCESS);

        // Assert type table created
        File tsvFile = new File(projectDir, "src/main/resources/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .isFile()
          .isReadable()
          .isNotEmpty();

        // Load classes from the type table
        TypeTable table = createTypeTable(tsvFile, "guava");
        assertThat(table.load("guava")).isDirectoryRecursivelyContaining("glob:**/Optional.class");
    }

    @Test
    void resolveFivePlusToActualVersion() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'org.openrewrite.build.recipe-library-base'
          }
          repositories {
              mavenCentral()
          }
          recipeDependencies {
              parserClasspath("org.springframework:spring-core:5.+") // Note the '+' in the version! Below we use `5.3`
          }
          """);

        BuildResult result = runTypeTableTaskAndSucceed();
        assertThat(requireNonNull(result.task(":createTypeTable")).getOutcome()).isEqualTo(SUCCESS);

        // Assert type table created
        File tsvFile = new File(projectDir, "src/main/resources/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .isFile()
          .isReadable()
          .isNotEmpty();

        // Load more specific `5.3` version from type table, which should not contain `5.+`
        TypeTable table = createTypeTable(tsvFile, "spring-core-5.3");
        assertThat(table.load("spring-core-5.3")).isDirectoryRecursivelyContaining("glob:**/Order.class");
    }

    @Test
    void usesDefaultIfMultipleResourcesDirectoriesHaveBeenDefined() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'java'
              id 'org.openrewrite.build.recipe-library-base'
          }
          repositories {
              mavenCentral()
          }
          recipeDependencies {
              parserClasspath 'com.google.guava:guava:30.1-jre'
          }
          sourceSets {
              main {
                  resources.srcDir('build/custom-resources')
              }
          }
          """);

        BuildResult result = runTypeTableTaskAndSucceed();
        assertThat(requireNonNull(result.task(":createTypeTable")).getOutcome()).isEqualTo(SUCCESS);

        // Assert type table created
        File tsvFile = new File(projectDir, "src/main/resources/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .isFile()
          .isReadable()
          .isNotEmpty();

        // Load classes from the type table
        TypeTable table = createTypeTable(tsvFile, "guava");
        assertThat(table.load("guava")).isDirectoryRecursivelyContaining("glob:**/Optional.class");
    }

    @Test
    void canSelectCustomResourcesDirectory() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'java'
              id 'org.openrewrite.build.recipe-library-base'
          }
          repositories {
              mavenCentral()
          }
          recipeDependencies {
              parserClasspath 'com.google.guava:guava:30.1-jre'
          }
          sourceSets.main {
              resources.srcDir('very/custom')
          }
          createTypeTable {
              targetDir = layout.projectDirectory.dir('very/custom')
          }
          """);

        BuildResult result = runTypeTableTaskAndSucceed();
        assertThat(requireNonNull(result.task(":createTypeTable")).getOutcome()).isEqualTo(SUCCESS);

        // Assert type table created
        File tsvFile = new File(projectDir, "very/custom/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .isFile()
          .isReadable()
          .isNotEmpty();

        // Load classes from the type table
        TypeTable table = createTypeTable(tsvFile, "guava");
        assertThat(table.load("guava")).isDirectoryRecursivelyContaining("glob:**/Optional.class");
    }

    @Test
    void throwsExceptionForNonExistentResourcesDirectory() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'org.openrewrite.build.recipe-library-base'
          }
          repositories {
              mavenCentral()
          }
          recipeDependencies {
              parserClasspath 'com.google.guava:guava:30.1-jre'
          }
          createTypeTable {
              targetDir = layout.projectDirectory.dir('very/custom')
          }
          """);

        BuildResult result = runTypeTableTaskAndFail();
        assertThat(requireNonNull(result.task(":createTypeTable")).getOutcome()).isEqualTo(FAILED);

        File tsvFile = new File(projectDir, "very/custom/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .doesNotExist();
    }

    @Test
    void sourceSetTestDependencies() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'java'
              id 'org.openrewrite.build.recipe-library-base'
          }
          repositories {
              mavenCentral()
          }
          recipeDependencies {
              parserClasspath 'com.google.guava:guava:30.1-jre'
              testParserClasspath 'org.junit.jupiter:junit-jupiter-api:5.10.0'
          }
          """);

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("createTestTypeTable", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":createTestTypeTable")).getOutcome()).isEqualTo(SUCCESS);

        // Assert type table created in test resources
        File tsvFile = new File(projectDir, "src/test/resources/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .isFile()
          .isReadable()
          .isNotEmpty();

        // Load classes from the type table
        TypeTable table = createTypeTable(tsvFile, "junit-jupiter-api");
        assertThat(table.load("junit-jupiter-api")).isDirectoryRecursivelyContaining("glob:**/Test.class");
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    void customSourceSetDependencies() throws Exception {
        createGradleBuildFiles("""
          plugins {
              id 'java'
              id 'org.openrewrite.build.recipe-library-base'
          }

          sourceSets {
              functionalTest {
                  java {
                      srcDir 'src/functionalTest/java'
                  }
                  resources {
                      srcDir 'src/functionalTest/resources'
                  }
              }
          }

          repositories {
              mavenCentral()
          }

          recipeDependencies {
              parserClasspath 'com.google.guava:guava:30.1-jre'
              parserClasspath 'functionalTest', 'org.assertj:assertj-core:3.24.2'
          }
          """);

        // Create the custom source set directories
        new File(projectDir, "src/functionalTest/java").mkdirs();
        new File(projectDir, "src/functionalTest/resources").mkdirs();

        BuildResult result = GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("createFunctionalTestTypeTable", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();

        assertThat(requireNonNull(result.task(":createFunctionalTestTypeTable")).getOutcome()).isEqualTo(SUCCESS);

        // Assert type table created in functionalTest resources
        File tsvFile = new File(projectDir, "src/functionalTest/resources/" + TypeTable.DEFAULT_RESOURCE_PATH);
        assertThat(tsvFile)
          .isFile()
          .isReadable()
          .isNotEmpty();

        // Load classes from the type table
        TypeTable table = createTypeTable(tsvFile, "assertj-core");
        assertThat(table.load("assertj-core")).isDirectoryRecursivelyContaining("glob:**/Assertions.class");
    }

    private void createGradleBuildFiles(@Language("gradle") String buildFileContent) throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'my-project'");
        Files.writeString(buildFile.toPath(), buildFileContent);
    }

    private BuildResult runTypeTableTaskAndSucceed() {
        return createGradleRunner().build();
    }

    private BuildResult runTypeTableTaskAndFail() {
        return createGradleRunner().buildAndFail();
    }

    private GradleRunner createGradleRunner() {
        return GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("createTypeTable", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true);
    }

    private static TypeTable createTypeTable(File tsvFile, String guava) throws Exception {
        Constructor<?> constructor = Class.forName("org.openrewrite.java.internal.parser.TypeTable")
          .getDeclaredConstructor(ExecutionContext.class, URL.class, Collection.class);
        constructor.setAccessible(true);
        return (TypeTable) constructor.newInstance(new InMemoryExecutionContext(), tsvFile.toURI().toURL(), singletonList(guava));
    }
}
