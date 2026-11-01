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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackpatchAllianceTest {

    private static final String BACKPATCH = "1.0.0.1-backpatch-00001";

    @Test
    void withoutCredentialsTheBuildIsAsItWas(@TempDir File projectDir) throws IOException {
        writeRepositoriesProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printRepositories", "-PbackpatchUsername=", "-PbackpatchPassword=")
                .build();

        assertThat(result.getOutput())
                .doesNotContain("repo:backpatch=")
                .doesNotContain("platform:");
    }

    @Test
    void withCredentialsTheAllianceIsAskedBeforeCodegenome(@TempDir File projectDir) throws IOException {
        writeRepositoriesProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printRepositories", "-PbackpatchUsername=test-user", "-PbackpatchPassword=bpa_test-token")
                .build();

        String output = result.getOutput();
        assertThat(output).contains("repo:backpatch=https://backpatch.moderne.io/maven/");
        assertThat(output.indexOf("repo:MavenLocal=")).isLessThan(output.indexOf("repo:backpatch="));
        assertThat(output.indexOf("repo:backpatch=")).isLessThan(output.indexOf("repo:codegenome="));
        assertThat(output.indexOf("repo:codegenome=")).isLessThan(output.indexOf("repo:MavenRepo="));
    }

    @Test
    void onlyTheMainClasspathsTakeThePlatform(@TempDir File projectDir) throws IOException {
        writeRepositoriesProject(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printRepositories", "-PbackpatchUsername=test-user", "-PbackpatchPassword=bpa_test-token")
                .build();

        assertThat(result.getOutput().lines().filter(line -> line.startsWith("platform:")))
                .containsExactlyInAnyOrder("platform:compileClasspath", "platform:runtimeClasspath");
    }

    @Test
    void thePomNamesEveryBackpatchTheBuildTookAndNeverThePlatform(@TempDir File dir) throws IOException {
        File projectDir = new File(dir, "project");
        try (FakeMirror alliance = new FakeMirror(new File(dir, "repository"))) {
            publishUpstreamAndBackpatches(alliance, new File(dir, "repository"));
            writePublishingProject(projectDir);

            BuildResult result = GradleRunner.create()
                    .withProjectDir(projectDir)
                    .withPluginClasspath()
                    .withEnvironment(alliance.environment())
                    .withArguments(arguments(alliance, true))
                    .build();

            String pom = pom(projectDir);
            assertThat(pom)
                    .doesNotContain("backpatch-platform")
                    .doesNotContain("<dependencyManagement>")
                    // Declared, at the backpatch the build resolved, and named once.
                    .containsOnlyOnce("<artifactId>direct</artifactId>")
                    .contains("<artifactId>direct</artifactId><version>" + BACKPATCH + "</version><scope>runtime</scope>")
                    // Through an implementation dependency: consumers only run with it.
                    .contains("<groupId>org.example</groupId><artifactId>lib</artifactId><version>" + BACKPATCH + "</version><scope>runtime</scope>")
                    // Through an api dependency: consumers compile against it too.
                    .contains("<groupId>org.example</groupId><artifactId>base</artifactId><version>" + BACKPATCH + "</version><scope>compile</scope>")
                    .contains("<artifactId>framework</artifactId><version>1.0.0</version><scope>compile</scope>");
            assertThat(result.getOutput())
                    .contains("runtimeClasspath org.example:lib:" + BACKPATCH)
                    .contains("testRuntimeClasspath org.example:lib:1.0.0");
        }
    }

    @Test
    void withoutCredentialsThePomNamesUpstreamReleases(@TempDir File dir) throws IOException {
        File projectDir = new File(dir, "project");
        try (FakeMirror alliance = new FakeMirror(new File(dir, "repository"))) {
            publishUpstreamAndBackpatches(alliance, new File(dir, "repository"));
            writePublishingProject(projectDir);

            BuildResult result = GradleRunner.create()
                    .withProjectDir(projectDir)
                    .withPluginClasspath()
                    .withEnvironment(alliance.environment())
                    .withArguments(arguments(alliance, false))
                    .build();

            assertThat(pom(projectDir))
                    .doesNotContain("-backpatch-")
                    .doesNotContain("<artifactId>lib</artifactId>")
                    .doesNotContain("<artifactId>base</artifactId>")
                    .contains("<artifactId>direct</artifactId><version>1.0.0</version><scope>runtime</scope>");
            assertThat(result.getOutput()).contains("runtimeClasspath org.example:lib:1.0.0");
        }
    }

    /**
     * Upstream releases, as Maven Central would serve them through the mirror, and the alliance's backpatches of three of
     * them with its platform, which constrains all three, in one repository the fake serves for both.
     */
    private static void publishUpstreamAndBackpatches(FakeMirror alliance, File root) throws IOException {
        alliance.publish("org.example", "lib", "1.0.0");
        alliance.publish("org.example", "direct", "1.0.0");
        alliance.publish("org.example", "base", "1.0.0");
        alliance.publishPom("org.example", "app", "1.0.0", dependsOn("lib"));
        alliance.publishPom("org.example", "framework", "1.0.0", dependsOn("base"));
        alliance.publish("org.example", "lib", BACKPATCH);
        alliance.publish("org.example", "direct", BACKPATCH);
        alliance.publish("org.example", "base", BACKPATCH);

        alliance.publishPom("io.moderne.backpatch", "backpatch-platform", "1",
                "<!-- do_not_remove: published-with-gradle-metadata --><packaging>pom</packaging>");
        String constraints = """
                [{"group": "org.example", "module": "lib", "version": {"requires": "%1$s"}},
                 {"group": "org.example", "module": "direct", "version": {"requires": "%1$s"}},
                 {"group": "org.example", "module": "base", "version": {"requires": "%1$s"}}]""".formatted(BACKPATCH);
        //language=json
        Files.writeString(new File(root, "io/moderne/backpatch/backpatch-platform/1/backpatch-platform-1.module").toPath(), """
                {
                  "formatVersion": "1.1",
                  "component": {"group": "io.moderne.backpatch", "module": "backpatch-platform", "version": "1",
                                "attributes": {"org.gradle.status": "release"}},
                  "variants": [
                    {"name": "apiElements",
                     "attributes": {"org.gradle.category": "platform", "org.gradle.usage": "java-api"},
                     "dependencyConstraints": %1$s},
                    {"name": "runtimeElements",
                     "attributes": {"org.gradle.category": "platform", "org.gradle.usage": "java-runtime"},
                     "dependencyConstraints": %1$s}
                  ]
                }
                """.formatted(constraints));
    }

    /** A POM's dependency on an upstream release of org.example's module of that name. */
    private static String dependsOn(String module) {
        //language=xml
        return """
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>%s</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """.formatted(module);
    }

    private static List<String> arguments(FakeMirror alliance, boolean credentials) {
        List<String> arguments = new ArrayList<>(List.of("generatePomFileForNebulaPublication", "printVersions",
                "-PcodegenomeUsername=", "-PcodegenomePassword=", "-PbackpatchUrl=" + alliance.url()));
        if (credentials) {
            arguments.add("-PbackpatchUsername=mirror-user");
            arguments.add("-PbackpatchPassword=mirror-token");
        } else {
            arguments.add("-PbackpatchUsername=");
            arguments.add("-PbackpatchPassword=");
        }
        return arguments;
    }

    private static void writeRepositoriesProject(File projectDir) throws IOException {
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'backpatch-consumer'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'org.openrewrite.build.recipe-repositories'
                }
                tasks.register('printRepositories') {
                    def names = repositories.collect { "repo:${it.name}=${it.hasProperty('url') ? it.url : ''}" }
                    def platforms = configurations.findAll { c -> c.extendsFrom.any { it.name == 'backpatchPlatform' } }
                            .collect { "platform:${it.name}" }
                    doLast { (names + platforms).each { println it } }
                }
                """);
    }

    private static void writePublishingProject(File projectDir) throws IOException {
        Files.createDirectories(projectDir.toPath());
        writeFile(new File(projectDir, "settings.gradle"), "rootProject.name = 'backpatch-consumer'");
        //language=groovy
        writeFile(new File(projectDir, "build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'org.openrewrite.build.recipe-repositories'
                    id 'org.openrewrite.build.publish'
                }
                group = 'org.example.recipes'
                version = '1.0.0'
                dependencies {
                    api 'org.example:framework:1.0.0'
                    implementation 'org.example:app:1.0.0'
                    implementation 'org.example:direct:1.0.0'
                    testImplementation 'org.example:lib:1.0.0'
                }
                tasks.register('printVersions') {
                    def classpaths = ['runtimeClasspath', 'testRuntimeClasspath'].collectEntries { name ->
                        [(name): configurations.getByName(name).incoming.resolutionResult.rootComponent]
                    }
                    doLast {
                        classpaths.each { name, root ->
                            root.get().dependencies*.selected*.id*.displayName.findAll { it.startsWith('org.example:lib') }
                                    .each { println "$name $it" }
                            def transitive = root.get().dependencies*.selected*.dependencies.flatten()*.selected*.id*.displayName
                            transitive.findAll { it.startsWith('org.example:lib') }.each { println "$name $it" }
                        }
                    }
                }
                """);
    }

    /** The generated POM with the whitespace between its tags taken out, however each element was indented. */
    private static String pom(File projectDir) throws IOException {
        return Files.readString(new File(projectDir, "build/publications/nebula/pom-default.xml").toPath())
                .replaceAll(">\\s+<", "><");
    }

    private static void writeFile(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content);
    }
}
