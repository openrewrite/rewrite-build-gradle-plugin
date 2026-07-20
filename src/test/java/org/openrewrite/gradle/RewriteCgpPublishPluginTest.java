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
import org.gradle.api.publish.PublishingExtension;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RewriteCgpPublishPluginTest {

    @Test
    void inertWithoutCredentials() {
        String awsAccessKey = System.getenv("AWS_ACCESS_KEY_ID");
        assumeTrue(awsAccessKey == null || awsAccessKey.isEmpty(), "AWS credentials present — plugin activates");
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("maven-publish");
        project.getPluginManager().apply(RewriteCgpPublishPlugin.class);
        assertThat(project.getExtensions().getByType(PublishingExtension.class).getRepositories().findByName("cgp")).isNull();
    }

    @Test
    void registersCgpS3RepositoryWithCredentials(@TempDir File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'cgp-consumer'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                    id 'org.openrewrite.build.publish-cgp'
                }
                group = 'com.example'
                version = '1.0.0-SNAPSHOT'
                publishing { publications { maven(MavenPublication) { from components.java } } }
                """);

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("AWS_ACCESS_KEY_ID", "test-access-key");
        env.put("AWS_SECRET_ACCESS_KEY", "test-secret-key");

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withEnvironment(env)
                .withArguments("publish", "--dry-run")
                .build();

        assertThat(result.getOutput()).contains(":publishMavenPublicationToCgpRepository");
    }

    private static void writeFile(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
