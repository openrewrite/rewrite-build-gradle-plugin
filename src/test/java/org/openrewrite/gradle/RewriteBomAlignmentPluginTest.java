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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class RewriteBomAlignmentPluginTest {
    @TempDir
    File projectDir;

    private File settingsFile;
    private File buildFile;
    private File repoDir;

    @BeforeEach
    void setup() throws IOException {
        settingsFile = new File(projectDir, "settings.gradle");
        buildFile = new File(projectDir, "build.gradle");
        repoDir = new File(projectDir, "local-repo");

        // Two versions of a managed transitive, plus two intermediates that pin different versions.
        // Group must be a subgroup of org.openrewrite (e.g. org.openrewrite.recipe) — the plugin's
        // managed-group filter requires a trailing dot, so the bare "org.openrewrite" group is ignored.
        publishPom("org.openrewrite.recipe", "core", "1.0.0", "");
        publishPom("org.openrewrite.recipe", "core", "2.0.0", "");
        publishPom("org.openrewrite.recipe", "bar", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """);
        publishPom("org.openrewrite.recipe", "baz", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
                """);
    }

    @Test
    void passesWhenTransitiveVersionsAlign() throws Exception {
        writeFile(settingsFile, "rootProject.name = 'aligned-bom'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(requireNonNull(result.task(":checkBomAlignment")).getOutcome()).isEqualTo(SUCCESS);
    }

    @Test
    void failsWhenTransitiveVersionsConflict() throws Exception {
        writeFile(settingsFile, "rootProject.name = 'misaligned-bom'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                    api 'org.openrewrite.recipe:baz:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        assertThat(result.getOutput())
                .contains("BOM dependency version mismatches")
                .contains("org.openrewrite.recipe:core")
                .contains("1.0.0")
                .contains("2.0.0")
                .contains("Release order to arrive at an aligned BOM")
                .containsPattern("Wave 1:[\\s\\S]*org\\.openrewrite\\.recipe:core[\\s\\S]*Wave 2:[\\s\\S]*org\\.openrewrite\\.recipe:bar[\\s\\S]*org\\.openrewrite\\.recipe:baz");
    }

    @Test
    void bomManagingStaleVersionIsFlaggedAsNeedingRelease() throws Exception {
        // A BOM that pins an older version of a managed module than what's actually being used in
        // the graph is itself out of date — its <dependencyManagement> needs refreshing.
        publishPom("org.openrewrite.recipe", "stale-bom", "1.0.0", "", """
                <scm>
                    <url>https://github.com/openrewrite/stale-bom</url>
                </scm>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);
        publishPom("org.openrewrite.recipe", "consumer-of-stale", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
                """, """
                <scm>
                    <url>https://github.com/openrewrite/consumer-of-stale</url>
                </scm>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>stale-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);

        writeFile(settingsFile, "rootProject.name = 'stale-bom-check'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:consumer-of-stale:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        // The BOM pins core at 1.0.0 in its <dependencyManagement>, while the graph already uses 2.0.0,
        // so the BOM itself needs a release and gets the ! marker.
        assertThat(result.getOutput())
                .contains("! https://github.com/openrewrite/stale-bom/releases")
                .contains("requests org.openrewrite.recipe:core:1.0.0 (latest 2.0.0)");
    }

    @Test
    void platformImportInPomAddsEdgeToReleaseOrder() throws Exception {
        // shared-bom is imported by the consumer's POM via <scope>import</scope>. The consumer never
        // appears in the resolution graph as depending on shared-bom (Gradle treats the import as a
        // version constraint), but for release ordering shared-bom must ship first because the
        // consumer needs to pick up its updated <dependencyManagement> on its next release.
        publishPom("org.openrewrite.recipe", "shared-bom", "1.0.0", "", """
                <scm>
                    <url>https://github.com/openrewrite/shared-bom</url>
                </scm>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);
        publishPom("org.openrewrite.recipe", "consumer", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """, """
                <scm>
                    <url>https://github.com/openrewrite/consumer</url>
                </scm>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>shared-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);

        writeFile(settingsFile, "rootProject.name = 'platform-import'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                    api 'org.openrewrite.recipe:baz:1.0.0'
                    api 'org.openrewrite.recipe:consumer:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        // Ordering: core (managed by shared-bom) ships first, shared-bom ships next (so its <dependencyManagement>
        // can point at the new core), then consumer (which imports shared-bom) ships last.
        assertThat(result.getOutput())
                .containsPattern("Wave 1:[\\s\\S]*?org\\.openrewrite\\.recipe:core[\\s\\S]*?Wave [2-9]:[\\s\\S]*?https://github\\.com/openrewrite/shared-bom/releases[\\s\\S]*?Wave [2-9]:[\\s\\S]*?https://github\\.com/openrewrite/consumer/releases");
    }

    @Test
    void isolatedRepoLandsInFinalWaveNotFirstWave() throws Exception {
        // standalone has no managed deps and nothing in the BOM depends on it — it's "isolated".
        // Without this rule it would land in Wave 1 (no deps, immediately ready). The rule defers it
        // to the final wave to handle the case where its POM understates its real coupling
        // (e.g. fatjar artifacts).
        publishPom("io.moderne", "standalone", "1.0.0", "");

        writeFile(settingsFile, "rootProject.name = 'isolated-repo'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                    api 'org.openrewrite.recipe:baz:1.0.0'
                    api 'io.moderne:standalone:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        // bar/baz/core land in waves 1-2; standalone is isolated and must be deferred to the final wave.
        String output = result.getOutput();
        int firstWave2Header = output.indexOf("Wave 2:");
        int firstStandalone = output.indexOf("io.moderne:standalone");
        assertThat(firstWave2Header).isPositive();
        assertThat(firstStandalone).isGreaterThan(firstWave2Header);
    }

    @Test
    void blockedRepoIsMarkedXWhenAnUpstreamRepoStillNeedsRelease() throws Exception {
        // Build a chain: target T (1.0.0 vs 2.0.0 mismatch), middle pins T:1.0.0, top pins both T:1.0.0 and middle:1.0.0.
        // BOM forces T to 2.0.0 — so middle and top both need release.
        // middle's only managed dep is T (already aligned to 2.0.0 by the BOM), so middle is ready: !
        // top's managed deps include middle, which still needs release, so top is blocked: x
        publishPom("org.openrewrite.recipe", "target", "1.0.0", "");
        publishPom("org.openrewrite.recipe", "target", "2.0.0", "");
        publishPom("org.openrewrite.recipe", "middle", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>target</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """);
        publishPom("org.openrewrite.recipe", "top", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>middle</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>target</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """);

        writeFile(settingsFile, "rootProject.name = 'blocked-repo'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:top:1.0.0'
                    api 'org.openrewrite.recipe:middle:1.0.0'
                    api 'org.openrewrite.recipe:target:2.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        assertThat(result.getOutput())
                .contains("Release order to arrive at an aligned BOM")
                // target is the alignment target (no own pin to bump), so its repo is ✓
                .contains("    ✓ org.openrewrite.recipe:target")
                // middle pins old target but its only managed dep (target) is at the latest, so it can ship now
                .contains("    ! org.openrewrite.recipe:middle")
                // top pins old target AND old middle; middle still needs to ship, so top is blocked
                .contains("    x org.openrewrite.recipe:top")
                // The wave containing only `top` (blocked) is itself marked x — nothing in it can ship yet.
                .containsPattern("x Wave [0-9]+:[\\s\\S]*?    x org\\.openrewrite\\.recipe:top");
    }

    @Test
    void releaseOrderGroupsArtifactsByScmRepoWithReleasesLink() throws Exception {
        // Two artifacts in the same repo (one depends on the other) plus a third in a different repo.
        // The repo with the depended-on artifact must be released first; co-located artifacts share a single link.
        publishPom("io.moderne.example", "lib-core", "1.0.0", "", """
                <scm>
                    <url>https://github.com/moderneinc/example-libs</url>
                </scm>
                """);
        publishPom("io.moderne.example", "lib-core", "2.0.0", "", """
                <scm>
                    <url>https://github.com/moderneinc/example-libs</url>
                </scm>
                """);
        publishPom("io.moderne.example", "lib-extra", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>io.moderne.example</groupId>
                        <artifactId>lib-core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                """, """
                <scm>
                    <url>scm:git:git@github.com:moderneinc/example-libs.git</url>
                </scm>
                """);
        publishPom("io.moderne.consumer", "consumer", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>io.moderne.example</groupId>
                        <artifactId>lib-core</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
                """, """
                <scm>
                    <url>https://github.com/moderneinc/consumer</url>
                </scm>
                """);

        writeFile(settingsFile, "rootProject.name = 'scm-grouped'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'io.moderne.example:lib-extra:1.0.0'
                    api 'io.moderne.consumer:consumer:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .buildAndFail();

        assertThat(result.getOutput())
                .contains("Release order to arrive at an aligned BOM")
                // example-libs is marked ! because lib-extra pins lib-core:1.0.0 while consumer pins the
                // newer lib-core:2.0.0 — so lib-extra needs a release. The marker appears only on the repo line;
                // individual artifact coordinates are listed without the marker.
                // Wave 1 contains the misaligned repo so the wave header itself is marked !.
                .contains("! Wave 1:")
                // Wave 2 is fully aligned (consumer pins the latest), so the wave header is marked ✓.
                .contains("✓ Wave 2:")
                .containsPattern("Wave 1:[\\s\\S]*    ! https://github\\.com/moderneinc/example-libs/releases[\\s\\S]*?io\\.moderne\\.example:lib-core[\\s\\S]*?io\\.moderne\\.example:lib-extra")
                .doesNotContain("! io.moderne.example:lib-core")
                .doesNotContain("! io.moderne.example:lib-extra")
                // Each outdated requester is followed by a "requests <module> <oldver> (latest <newver>)" detail line
                // so the user can see exactly why the repo got flagged.
                .contains("requests io.moderne.example:lib-core:1.0.0 (latest 2.0.0)")
                // consumer already pins the newest lib-core, so the repo gets the aligned ✓ marker.
                .contains("    ✓ https://github.com/moderneinc/consumer/releases")
                .doesNotContain("! https://github.com/moderneinc/consumer/releases")
                .doesNotContain("! io.moderne.consumer:consumer");
    }

    @Test
    void ignoresMismatchesIntroducedByThirdPartyRequesters() throws Exception {
        // A non-managed third party depends on managed core 2.0.0; the BOM also pins core 1.0.0 directly.
        // The third-party-introduced disagreement must be ignored — only managed-on-managed disagreements should fail.
        publishPom("com.example.thirdparty", "lib", "1.0.0", """
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>core</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
                """);

        writeFile(settingsFile, "rootProject.name = 'third-party-requester'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    api 'org.openrewrite.recipe:bar:1.0.0'
                    api 'com.example.thirdparty:lib:1.0.0'
                }
                """.formatted(repoDir.toURI());

        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("checkBomAlignment", "--stacktrace")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(requireNonNull(result.task(":checkBomAlignment")).getOutcome()).isEqualTo(SUCCESS);
    }

    @Test
    void inheritsFromAddsParentBomManagedDepsAsDirectApiEntries() throws Exception {
        // Parent BOM manages bar:1.0.0 in <dependencyManagement>.
        publishPom("org.openrewrite.recipe", "parent-bom", "1.0.0", "", """
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>bar</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);

        writeFile(settingsFile, "rootProject.name = 'inherits-from'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    bomAlignment.inheritsFrom('org.openrewrite.recipe:parent-bom:1.0.0')
                }
                tasks.register('listApi') {
                    doLast {
                        configurations.api.dependencies.each {
                            println "API ${it.group}:${it.name}:${it.version}"
                        }
                    }
                }
                """.formatted(repoDir.toURI());
        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("listApi")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        assertThat(result.getOutput()).contains("API org.openrewrite.recipe:bar:1.0.0");
    }

    @Test
    void inheritsFromFollowsImportScopedTransitiveBoms() throws Exception {
        // inner-bom manages baz:1.0.0; outer-bom imports inner-bom and adds bar:1.0.0.
        publishPom("org.openrewrite.recipe", "inner-bom", "1.0.0", "", """
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>baz</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);
        publishPom("org.openrewrite.recipe", "outer-bom", "1.0.0", "", """
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>inner-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.openrewrite.recipe</groupId>
                            <artifactId>bar</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                """);

        writeFile(settingsFile, "rootProject.name = 'inherits-from-transitive'");

        //language=groovy
        String buildFileContent = """
                plugins {
                    id 'java-platform'
                    id 'org.openrewrite.build.bom-alignment'
                }
                javaPlatform { allowDependencies() }
                repositories {
                    maven { url = uri('%s') }
                }
                dependencies {
                    bomAlignment.inheritsFrom('org.openrewrite.recipe:outer-bom:1.0.0')
                }
                tasks.register('listApi') {
                    doLast {
                        configurations.api.dependencies.each {
                            println "API ${it.group}:${it.name}:${it.version}"
                        }
                    }
                }
                """.formatted(repoDir.toURI());
        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("listApi")
                .withPluginClasspath()
                .withDebug(true)
                .build();

        // Both the directly-managed dep (bar) and the import-scoped dep (baz) should be enumerated
        // as direct api entries — the import is followed transitively.
        assertThat(result.getOutput())
                .contains("API org.openrewrite.recipe:bar:1.0.0")
                .contains("API org.openrewrite.recipe:baz:1.0.0");
    }

    private void publishPom(String group, String artifact, String version, String depsXml) throws IOException {
        publishPom(group, artifact, version, depsXml, "");
    }

    private void publishPom(String group, String artifact, String version, String depsXml, String extraXml) throws IOException {
        File dir = new File(repoDir, group.replace('.', '/') + "/" + artifact + "/" + version);
        assertThat(dir.mkdirs()).isTrue();
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                    %s
                    %s
                </project>
                """.formatted(group, artifact, version, depsXml, extraXml);
        writeFile(new File(dir, artifact + "-" + version + ".pom"), pom);
    }

    private void writeFile(File destination, String content) throws IOException {
        Files.write(destination.toPath(), content.getBytes());
    }
}
