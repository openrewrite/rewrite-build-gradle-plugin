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
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.tasks.Jar;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class ModerneProprietaryLicensePluginTest {

    @ParameterizedTest
    @CsvSource({
            "emptySourceJar, sources",
            "emptyJavadocJar, javadoc"
    })
    void emptyJarPackagesNothingFromTheProject(String taskName, String classifier, @TempDir File projectDir) throws IOException {
        Files.writeString(new File(projectDir, "README.md").toPath(), "# Not for publication");
        Files.writeString(new File(projectDir, "LICENSE").toPath(), "Moderne Proprietary License");

        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        applyPlugins(project);

        Jar emptyJar = (Jar) project.getTasks().getByName(taskName);
        assertThat(emptyJar.getArchiveClassifier().get()).isEqualTo(classifier);
        assertThat(packagedFiles(emptyJar)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"sourcesJar", "javadocJar"})
    void realJarIsDisabled(String taskName, @TempDir File projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build();
        applyPlugins(project);

        assertThat(project.getTasks().getByName(taskName).getEnabled()).isFalse();
    }

    @Test
    void publishedSourcesAndJavadocJarsAreEmpty(@TempDir File projectDir) throws IOException {
        Files.writeString(new File(projectDir, "settings.gradle").toPath(), "rootProject.name = 'proprietary-consumer'");
        Files.writeString(new File(projectDir, "README.md").toPath(), "# Not for publication");
        Files.writeString(new File(projectDir, "gradle.properties").toPath(), """
                group=com.example
                version=1.0.0-SNAPSHOT
                """);
        //language=groovy
        Files.writeString(new File(projectDir, "build.gradle").toPath(), """
                plugins {
                    id 'java-library'
                    id 'org.openrewrite.build.publish'
                    id 'org.openrewrite.build.moderne-proprietary-license'
                }
                publishing {
                    repositories {
                        maven { name = 'testRepo'; url = layout.buildDirectory.dir('repo') }
                    }
                }
                """);

        GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("publishNebulaPublicationToTestRepoRepository")
                .build();

        File published = new File(projectDir, "build/repo/com/example/proprietary-consumer/1.0.0-SNAPSHOT");
        for (String classifier : List.of("sources", "javadoc")) {
            File[] jars = published.listFiles((dir, name) -> name.endsWith("-" + classifier + ".jar"));
            assertThat(jars).as("published %s jar", classifier).isNotNull().hasSize(1);
            try (JarFile jar = new JarFile(jars[0])) {
                assertThat(jar.stream().map(JarEntry::getName))
                        .as("entries of %s", jars[0].getName())
                        .allMatch(name -> name.startsWith("META-INF/"));
            }
        }
    }

    private static void applyPlugins(Project project) {
        project.getPluginManager().apply("java-library");
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.withSourcesJar();
        java.withJavadocJar();
        project.getPluginManager().apply(ModerneProprietaryLicensePlugin.class);
    }

    private static Set<File> packagedFiles(Jar jar) throws IOException {
        Set<File> files = new LinkedHashSet<>();
        for (File file : jar.getSource().getFiles()) {
            if (!"MANIFEST.MF".equals(file.getName())) {
                files.add(file.getCanonicalFile());
            }
        }
        return files;
    }
}
