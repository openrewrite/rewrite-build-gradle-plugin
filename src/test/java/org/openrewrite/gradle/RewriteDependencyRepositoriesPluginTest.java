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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RewriteDependencyRepositoriesPluginTest {

    @Test
    void codegenomeAbsentWithoutCredentials(@TempDir File projectDir) throws IOException {
        String username = System.getenv("ORG_GRADLE_PROJECT_codegenomeUsername");
        assumeTrue(username == null || username.isEmpty(), "CGP credentials present — repository activates");
        writeProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printRepositories")
                .build();

        assertThat(result.getOutput()).doesNotContain("repo:codegenome");
        assertThat(result.getOutput()).contains("repo:MavenRepo");
    }

    @Test
    void registersCodegenomeRepositoryWithCredentials(@TempDir File projectDir) throws IOException {
        writeProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printRepositories",
                        "-PcodegenomeUsername=test-user",
                        "-PcodegenomePassword=cgp_test-token")
                .build();

        assertThat(result.getOutput()).contains("repo:codegenome=https://artifacts.codegenomeproject.org/maven");
    }

    @Test
    void codegenomeResolvedBeforeMavenCentral(@TempDir File projectDir) throws IOException {
        writeProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printRepositories",
                        "-PcodegenomeUsername=test-user",
                        "-PcodegenomePassword=cgp_test-token")
                .build();

        String output = result.getOutput();
        assertThat(output).doesNotContain("https://central.sonatype.com/repository/maven-snapshots/");
        assertThat(output.indexOf("repo:codegenome="))
                .isLessThan(output.indexOf("repo:MavenRepo="));
    }

    @Test
    void rewriteArtifactsIncludingToolsForksResolveFromCodegenome(@TempDir File projectDir) throws IOException {
        String username = System.getenv("ORG_GRADLE_PROJECT_codegenomeUsername");
        assumeTrue(username != null && !username.isEmpty(), "CGP credentials absent — Maven Central stays the fallback");
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'cgp-consumer'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'org.openrewrite.build.recipe-repositories'
                }
                dependencies {
                    implementation platform('org.openrewrite:rewrite-bom:latest.release')
                    implementation 'org.openrewrite.tools:java-object-diff:1.0.2'
                }
                tasks.register('resolve') {
                    def files = configurations.compileClasspath
                    doLast { files.resolve() }
                }
                """);

        File emptyMavenLocal = new File(projectDir, "empty-m2");
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("resolve", "--refresh-dependencies", "--info",
                        "-Dmaven.repo.local=" + emptyMavenLocal.getAbsolutePath())
                .build();

        String output = result.getOutput();
        assertThat(output).contains("https://artifacts.codegenomeproject.org/maven/org/openrewrite/rewrite-bom");
        assertThat(output).contains("https://artifacts.codegenomeproject.org/maven/org/openrewrite/tools/java-object-diff");
        assertThat(output).doesNotContain("https://repo.maven.apache.org/maven2/org/openrewrite");
    }

    private static void writeProject(File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'cgp-consumer'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'org.openrewrite.build.recipe-repositories'
                }
                tasks.register('printRepositories') {
                    def names = repositories.collect { "repo:${it.name}=${it.hasProperty('url') ? it.url : ''}" }
                    doLast { names.each { println it } }
                }
                """);
    }

    private static void writeFile(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
