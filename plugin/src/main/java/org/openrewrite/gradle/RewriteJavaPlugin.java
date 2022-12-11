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

import nebula.plugin.publishing.publications.JavadocJarPlugin;
import nebula.plugin.publishing.publications.SourceJarPlugin;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLoggingContainer;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.testretry.TestRetryPlugin;
import org.gradle.testretry.TestRetryTaskExtension;
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions;
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformJvmPlugin;

import java.util.HashMap;

public class RewriteJavaPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RewriteJavaExtension ext = project.getExtensions().create("rewriteJava", RewriteJavaExtension.class);
        ext.getKotlinTests().convention(false);
        ext.getJacksonVersion().convention("2.13.4.20221013");

        project.getPlugins().apply(JavaLibraryPlugin.class);
        project.getPlugins().apply(SourceJarPlugin.class);
        project.getPlugins().apply(JavadocJarPlugin.class);
        project.getPlugins().apply(TestRetryPlugin.class);

        project.getExtensions().configure(JavaPluginExtension.class, java -> java.toolchain(toolchain -> toolchain.getLanguageVersion()
                .set(JavaLanguageVersion.of(17))));

        project.getConfigurations().all(config -> {
            config.exclude(new HashMap<String, String>() {{
                put("group", "com.google.errorprone");
                put("module", "*");
            }});
            config.resolutionStrategy(strategy ->
                    strategy.cacheDynamicVersionsFor(0, "seconds"));
        });

        addDependencies(project, ext);
        configureJavaCompile(project);
        configureTesting(project);
        maybeConfigureKotlin(project, ext);

        project.getTasks().withType(Javadoc.class).configureEach(task -> {
            task.setVerbose(false);
            task.options(opt -> {
                ((CoreJavadocOptions) opt)
                        .addStringOption("Xdoclint:none", "-quiet");
                opt.encoding("UTF-8");
            });
        });
    }

    private static void addDependencies(Project project, RewriteJavaExtension ext) {
        DependencyHandler deps = project.getDependencies();
        deps.add("compileOnly", "org.projectlombok:lombok:latest.release");
        deps.add("testCompileOnly", "org.projectlombok:lombok:latest.release");
        deps.add("annotationProcessor", "org.projectlombok:lombok:latest.release");

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
        project.getTasks().withType(JavaCompile.class).getByName("compileJava", task -> {
            task.getOptions().getRelease().set(8);
        });

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getCompilerArgs().add("-parameters");
            task.getOptions().setFork(true);
        });
    }

    private static void configureTesting(Project project) {
        project.getExtensions().configure(TestRetryTaskExtension.class, ext -> {
            ext.getMaxFailures().set(4);
        });

        project.getTasks().named("test", Test.class, task -> {
            task.getExtensions().getExtraProperties().set(
                    "maxParallelForks",
                    Runtime.getRuntime().availableProcessors() / 2 > 0 ?
                            Runtime.getRuntime().availableProcessors() : 1
            );
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

    private static void maybeConfigureKotlin(Project project, RewriteJavaExtension ext) {
        if (!ext.getKotlinTests().get()) {
            return;
        }

        project.getPlugins().apply(KotlinPlatformJvmPlugin.class);

        project.getTasks().withType(JavaCompile.class).getByName("compileJava", task -> {
            task.setSourceCompatibility("1.8");
            task.setTargetCompatibility("1.8");
        });

        project.getTasks().withType(KotlinCompile.class).configureEach(task -> {
            //noinspection unchecked
            task.kotlinOptions(((Action<KotlinJvmOptions>) opt -> opt.setJvmTarget("1.8")));
        });

        DependencyHandler deps = project.getDependencies();
        deps.add("testImplementation", deps.platform("org.jetbrains.kotlin:kotlin-bom"));
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-reflect");
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib");
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib-jdk8");
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib-common");
    }
}
