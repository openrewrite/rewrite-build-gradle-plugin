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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLoggingContainer;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.testing.base.TestingExtension;
import org.gradle.testretry.TestRetryPlugin;
import org.gradle.testretry.TestRetryTaskExtension;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RewriteJavaPlugin implements Plugin<Project> {

    private static final Attribute<String> CONFIGURATION_ORIGIN_ATTRIBUTE =
            Attribute.of("org.openrewrite.configuration.origin", String.class);

    private static final int JUNIT_BOM_THRESHOLD = 17;
    private static final String JUNIT5_BOM_VERSION = "5.+";
    private static final String JUNIT6_BOM_VERSION = "6.+";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(RewriteArtifactoryMirrorPlugin.class);
        project.getPlugins().apply(RewriteDependencyCheckPlugin.class);

        RewriteJavaExtension ext = project.getExtensions().create("rewriteJava", RewriteJavaExtension.class);
        ext.getJacksonVersion().convention("2.21.7");

        project.getPlugins().apply(JavaLibraryPlugin.class);

        // Fix Gradle 9.0+ configuration attribute conflicts between archives and signatures
        // If the archives configuration is removed, we need to also remove this attribute addition
        project.getConfigurations().named("archives", config ->
            config.getAttributes().attribute(CONFIGURATION_ORIGIN_ATTRIBUTE, "java-plugin"));

        project.getConfigurations().all(config -> {
            config.getResolutionStrategy().cacheChangingModulesFor(0, TimeUnit.SECONDS);
            config.getResolutionStrategy().cacheDynamicVersionsFor(0, TimeUnit.SECONDS);
            // Error Prone 2.51.0+ depends on versions of these that require Java 9 and 11
            config.getDependencies().withType(ModuleDependency.class).configureEach(dep -> {
                if ("com.google.errorprone".equals(dep.getGroup()) && "error_prone_core".equals(dep.getName())) {
                    dep.exclude(Map.of("group", "org.pcollections", "module", "pcollections"));
                    dep.exclude(Map.of("group", "com.github.ben-manes.caffeine", "module", "caffeine"));
                }
            });
        });

        project.getExtensions().configure(JavaPluginExtension.class, java ->
                java.toolchain(toolchain -> toolchain.getLanguageVersion().set(JavaLanguageVersion.of(21))));

        project.getConfigurations().all(config -> config.resolutionStrategy(strategy ->
                strategy.cacheDynamicVersionsFor(0, "seconds")));

        project.getDependencies().getComponents().all(PreReleaseStatusRule.class);

        addDependencies(project, ext);
        configureJavaCompile(project);
        configureTesting(project);

        project.getTasks().withType(Javadoc.class).configureEach(task ->
            task.options(opt -> {
                ((CoreJavadocOptions) opt)
                        .addStringOption("Xdoclint:none", "-quiet");
                opt.encoding("UTF-8");
            }));

        // Work around build error relating to an IntelliJ file named classpath.index that is locally being included
        // multiple times within our jars. Unclear why this is happening, but it would not affect CI so working around for now
        project.getTasks().withType(Jar.class).configureEach(task -> task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE));
    }

    private static void addDependencies(Project project, RewriteJavaExtension ext) {
        DependencyHandler deps = project.getDependencies();
        deps.add("compileOnly", "org.projectlombok:lombok:latest.release");
        deps.add("testCompileOnly", "org.projectlombok:lombok:latest.release");
        deps.add("annotationProcessor", "org.projectlombok:lombok:latest.release");
        deps.add("testAnnotationProcessor", "org.projectlombok:lombok:latest.release");
        deps.add("api", deps.platform("com.fasterxml.jackson:jackson-bom:" + ext.getJacksonVersion().get()));

        deps.add("implementation", "org.jetbrains:annotations:latest.release");
        deps.add("compileOnly", "com.google.code.findbugs:jsr305:latest.release");

        deps.add("testImplementation", "org.junit.jupiter:junit-jupiter-api");
        deps.add("testImplementation", "org.junit.jupiter:junit-jupiter-params");
        deps.add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine");
        deps.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher");

        deps.add("testImplementation", "org.assertj:assertj-core:latest.release");
    }

    private static void configureJavaCompile(Project project) {
        project.getTasks().named("compileJava", JavaCompile.class, task -> task.getOptions().getRelease().set(8));

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getCompilerArgs().add("-parameters");
            task.getOptions().setFork(true);
        });
    }

    private static String pickBomVersion(Project project) {
        JavaLanguageVersion v = project.getExtensions().getByType(JavaPluginExtension.class)
                .getToolchain().getLanguageVersion().getOrNull();
        int targetJvm = v != null ? v.asInt() : Runtime.version().feature();
        return targetJvm >= JUNIT_BOM_THRESHOLD ? JUNIT6_BOM_VERSION : JUNIT5_BOM_VERSION;
    }

    private static void configureTesting(Project project) {
        // Inject the JUnit BOM into every JvmTestSuite (default `test` plus any registered later).
        // The dependency is registered eagerly via addProvider; the version lambda runs lazily,
        // so build-script toolchain overrides (e.g. rewrite-java-8 pinning to 8) have applied
        // by the time we pick a BOM line — no afterEvaluate required.
        project.getExtensions().configure(TestingExtension.class, testing ->
                testing.getSuites().withType(JvmTestSuite.class).configureEach(suite -> {
                    String implName = suite.getSources().getImplementationConfigurationName();
                    project.getDependencies().addProvider(implName,
                            project.provider(() -> project.getDependencies().platform(
                                    "org.junit:junit-bom:" + pickBomVersion(project))));
                }));

        project.getPlugins().apply(TestRetryPlugin.class);
        boolean ci = System.getenv("CI") != null;

        ArtifactoryMirror mirror = ArtifactoryMirror.ifAvailable(project.getProviders());

        project.getTasks().withType(Test.class).configureEach(task -> {
            // The same variables CI sets, so local test runs resolve through the mirror rather than Maven Central
            if (mirror != null) {
                task.environment(mirror.environment());
            }

            // On CI a test that fails runs once more, in a new test JVM, and fails the task only if it fails again,
            // unless ten or more of the task's tests failed: that is a break, not a flaky test. Off CI nothing runs
            // again, so a flaky test shows itself to whoever is working on it. Neither number is an input of the
            // task, so CI and developers' machines share its build cache entries. If an init script applied its own
            // copy of the plugin first, the task's extension is of that copy's type, and its settings stand.
            TestRetryTaskExtension retry = task.getExtensions().findByType(TestRetryTaskExtension.class);
            if (retry != null) {
                retry.getMaxRetries().convention(ci ? 1 : 0);
                retry.getMaxFailures().convention(10);
                retry.getFailOnPassedAfterRetry().convention(false);
            }
            // Each attempt stays a testcase of its own in the JUnit XML, the retry after the first round's, which is
            // how a report tells a test that passed on its retry from one that passed the first time
            task.getReports().getJunitXml().getMergeReruns().convention(false);

            if (!ci) {
                // Developer machines typically use CPUs with hyper-threading, so the logical core count is double
                // what is useful to enable
                task.setMaxParallelForks(
                        Runtime.getRuntime().availableProcessors() / 2 > 0 ?
                                Runtime.getRuntime().availableProcessors() / 2 : 1
                );
            } else {
                task.setMaxParallelForks(Runtime.getRuntime().availableProcessors());
            }
            task.useJUnitPlatform(junit -> junit.excludeTags("debug"));
            task.jvmArgs(
                    "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+ShowHiddenFrames"
            );

            TestLoggingContainer log = task.getTestLogging();
            log.setShowExceptions(true);
            log.setExceptionFormat(TestExceptionFormat.FULL);
            log.setShowCauses(true);
            log.setShowStackTraces(true);
        });
    }
}
