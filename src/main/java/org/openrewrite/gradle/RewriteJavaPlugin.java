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
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLoggingContainer;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import java.util.concurrent.TimeUnit;

public class RewriteJavaPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(RewriteDependencyCheckPlugin.class);

        RewriteJavaExtension ext = project.getExtensions().create("rewriteJava", RewriteJavaExtension.class);
        ext.getJacksonVersion().convention("2.17.2");

        project.getPlugins().apply(JavaLibraryPlugin.class);

        project.getConfigurations().all(config -> {
            config.getResolutionStrategy().cacheChangingModulesFor(0, TimeUnit.SECONDS);
            config.getResolutionStrategy().cacheDynamicVersionsFor(0, TimeUnit.SECONDS);
        });

        project.getExtensions().configure(JavaPluginExtension.class, java -> java.toolchain(toolchain -> toolchain.getLanguageVersion()
                .set(JavaLanguageVersion.of(17))));

        project.getConfigurations().all(config -> config.resolutionStrategy(strategy ->
                strategy.cacheDynamicVersionsFor(0, "seconds")));

        addDependencies(project, ext);
        configureJavaCompile(project);
        configureTesting(project);

        project.getTasks().withType(Javadoc.class).configureEach(task -> {
            task.options(opt -> {
                ((CoreJavadocOptions) opt)
                        .addStringOption("Xdoclint:none", "-quiet");
                opt.encoding("UTF-8");
            });
        });

        // Work around build error relating to an IntelliJ file named classpath.index that is locally being included
        // multiple times within our jars. Unclear why this is happening, but it would not affect CI so working around for now
        project.getTasks().withType(Jar.class).configureEach(task -> task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE));
    }

    private static void addDependencies(Project project, RewriteJavaExtension ext) {
        DependencyHandler deps = project.getDependencies();
        deps.add("compileOnly", "org.openrewrite.tools:lombok:latest.release");
        deps.add("testCompileOnly", "org.openrewrite.tools:lombok:latest.release");
        deps.add("annotationProcessor", "org.openrewrite.tools:lombok:latest.release");
        deps.add("testAnnotationProcessor", "org.openrewrite.tools:lombok:latest.release");
        deps.add("api", deps.platform("com.fasterxml.jackson:jackson-bom:" + ext.getJacksonVersion().get()));

        deps.add("implementation", "org.jetbrains:annotations:latest.release");
        deps.add("compileOnly", "com.google.code.findbugs:jsr305:latest.release");

        deps.add("testImplementation", deps.platform("org.junit:junit-bom:latest.release"));
        deps.add("testImplementation", "org.junit.jupiter:junit-jupiter-api");
        deps.add("testImplementation", "org.junit.jupiter:junit-jupiter-params");
        deps.add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine");

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

    private static void configureTesting(Project project) {
//        project.getTasks().withType(Test.class).configureEach(task ->
//                project.getExtensions().configure(TestRetryTaskExtension.class, ext ->
//                        ext.getMaxFailures().set(4))
//        );

        project.getTasks().withType(Test.class).configureEach(task -> {
            if (System.getenv("CI") == null) {
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
