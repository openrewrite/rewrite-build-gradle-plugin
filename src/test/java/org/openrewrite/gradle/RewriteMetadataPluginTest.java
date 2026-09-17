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

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class RewriteMetadataPluginTest {
    @TempDir
    Path testProjectDir;

    @TempDir
    Path buildCacheDir;

    @Test
    void shadowJarCacheEntriesAreKeyedByVersion() throws Exception {
        // Groovy expands backslash escapes inside single quotes, and Gradle takes forward slashes on Windows
        String cacheDir = buildCacheDir.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(testProjectDir.resolve("settings.gradle"),
          //language=gradle
          """
            rootProject.name = 'metadata'
            buildCache {
                local {
                    directory = '%s'
                }
            }
            """.formatted(cacheDir));
        Files.writeString(testProjectDir.resolve("build.gradle"),
          //language=gradle
          """
            plugins {
                id 'org.openrewrite.build.shadow'
                id 'org.openrewrite.build.metadata'
            }

            group = 'org.openrewrite'
            version = project.property('v')
            """);
        Path source = testProjectDir.resolve("src/main/java/org/openrewrite/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.openrewrite; public class Sample {}");

        assertThat(shadowJar("1.0.0-SNAPSHOT")).isEqualTo(new Provenance(SUCCESS, "1.0.0-SNAPSHOT", "1.0.0-SNAPSHOT"));
        assertThat(shadowJar("1.0.0")).isEqualTo(new Provenance(SUCCESS, "1.0.0", "1.0.0"));

        // an unchanged version still hits, so PR and main builds at the same snapshot keep sharing entries
        assertThat(shadowJar("1.0.0-SNAPSHOT")).isEqualTo(new Provenance(FROM_CACHE, "1.0.0-SNAPSHOT", "1.0.0-SNAPSHOT"));
    }

    private record Provenance(TaskOutcome outcome, String manifestVersion, String propertiesVersion) {
    }

    private Provenance shadowJar(String version) throws Exception {
        Path buildDir = testProjectDir.resolve("build");
        if (Files.exists(buildDir)) {
            try (Stream<Path> paths = Files.walk(buildDir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }

        BuildResult result = GradleRunner.create()
          .withProjectDir(testProjectDir.toFile())
          .withArguments("--build-cache", "-Pv=" + version, "shadowJar")
          .withPluginClasspath()
          .build();

        File jar = new File(buildDir.toFile(), "libs/metadata-" + version + ".jar");
        assertThat(jar).exists();
        try (JarFile jarFile = new JarFile(jar)) {
            // META-INF/<module>.properties carries the same field set, so it has to track the version too
            Properties properties = new Properties();
            try (InputStream in = jarFile.getInputStream(
              requireNonNull(jarFile.getEntry("META-INF/metadata.properties")))) {
                properties.load(in);
            }
            return new Provenance(
              requireNonNull(result.task(":shadowJar")).getOutcome(),
              requireNonNull(jarFile.getManifest()).getMainAttributes().getValue("Implementation-Version"),
              properties.getProperty("Implementation-Version"));
        }
    }
}
