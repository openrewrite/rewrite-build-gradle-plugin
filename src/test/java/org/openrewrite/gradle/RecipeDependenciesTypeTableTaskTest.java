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
import org.jspecify.annotations.Nullable;
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
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeDependenciesTypeTableTaskTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;

    @BeforeEach
    public void setup() {
        settingsFile = new File(projectDir, "settings.gradle");
        buildFile = new File(projectDir, "build.gradle");
    }

    @Test
    void multipleVersionsOfGuava() throws Exception {
        BuildResult result = createSourceFiles("""
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

        assertEquals(SUCCESS, requireNonNull(result.task(":createTypeTable")).getOutcome());

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
        BuildResult result = createSourceFiles("""
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

        assertEquals(SUCCESS, requireNonNull(result.task(":createTypeTable")).getOutcome());

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

    private BuildResult createSourceFiles(@Language("gradle") String source) throws IOException {
        Files.writeString(settingsFile.toPath(), "rootProject.name = 'my-project'");
        Files.writeString(buildFile.toPath(), source);
        return GradleRunner.create()
          .withProjectDir(projectDir)
          .withArguments("createTypeTable", "--info", "--stacktrace")
          .withPluginClasspath()
          .withDebug(true)
          .build();
    }

    private static @Nullable TypeTable createTypeTable(File tsvFile, String guava) throws Exception {
        Constructor<?> constructor = Class.forName("org.openrewrite.java.internal.parser.TypeTable")
          .getDeclaredConstructor(ExecutionContext.class, URL.class, Collection.class);
        constructor.setAccessible(true);
        return (TypeTable) constructor.newInstance(new InMemoryExecutionContext(), tsvFile.toURI().toURL(), singletonList(guava));
    }
}
