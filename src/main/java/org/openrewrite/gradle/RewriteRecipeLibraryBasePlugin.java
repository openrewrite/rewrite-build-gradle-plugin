/*
 * Copyright 2024 the original author or authors.
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
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.testing.base.TestingExtension;

@SuppressWarnings("UnstableApiUsage")
public class RewriteRecipeLibraryBasePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RewriteRecipeLibraryExtension ext = project.getExtensions().create("rewriteRecipe", RewriteRecipeLibraryExtension.class);
        ext.getRewriteVersion().convention(project.hasProperty("releasing") ?
                "latest.release" : "latest.integration");

        project.getPlugins().apply(RewriteJavaPlugin.class);
        project.getPlugins().apply(RewriteRecipeMarketplacePlugin.class);

        project.getExtensions().create("recipeDependencies", RecipeDependenciesExtension.class);

        // Register base tasks for backward compatibility
        project.getTasks().register("createTypeTable", RecipeDependenciesTypeTableTask.class, task -> {
            task.getSourceSetName().convention("main");
            RecipeDependenciesExtension extension = project.getExtensions().getByType(RecipeDependenciesExtension.class);
            task.getRecipeDependenciesClasspath().from(
                project.provider(() -> extension.getResolvedForSourceSet("main").values())
            );
        });
        project.getTasks().register("downloadRecipeDependencies", RecipeDependenciesDownloadTask.class);

        // Configure source set specific tasks when Java plugin is applied
        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);

            javaExt.getSourceSets().all(sourceSet -> {
                String sourceSetName = sourceSet.getName();

                // Register task for each source set
                String taskName = "main".equals(sourceSetName) ? "createTypeTable" :
                        "create" + capitalize(sourceSetName) + "TypeTable";

                if (!"main".equals(sourceSetName)) {
                    project.getTasks().register(taskName, RecipeDependenciesTypeTableTask.class, task -> {
                        task.getSourceSetName().convention(sourceSetName);
                        task.getTargetDir().convention(
                            project.getLayout().getProjectDirectory().dir("src/" + sourceSetName + "/resources")
                        );
                        RecipeDependenciesExtension extension = project.getExtensions().getByType(RecipeDependenciesExtension.class);
                        task.getRecipeDependenciesClasspath().from(
                            project.provider(() -> extension.getResolvedForSourceSet(sourceSetName).values())
                        );
                    });
                }
            });

            configureCliCompatSuite(project);
        });
    }

    /**
     * Register the {@code cliCompat} test suite — a dedicated source set
     * ({@code src/cliCompat/java}) whose tests drive recipe execution through a
     * pinned older moderne-cli via {@code org.openrewrite.test.ModwRunner}, catching
     * breaking changes in the recipe runtime contract.
     * <p>
     * The suite is deliberately <strong>not</strong> wired into {@code check}/{@code build},
     * so neither the regular {@code test} task nor {@code mod build} runs it; CI
     * invokes {@code ./gradlew cliCompat} on the nightly schedule. Every recipe
     * library gets the suite for free; modules that ship no {@code src/cliCompat}
     * tree simply get an empty, no-op {@code cliCompat} task.
     */
    private static void configureCliCompatSuite(Project project) {
        project.getPlugins().apply(JvmTestSuitePlugin.class);
        TestingExtension testing = project.getExtensions().getByType(TestingExtension.class);
        RewriteRecipeLibraryExtension recipeExt = project.getExtensions().getByType(RewriteRecipeLibraryExtension.class);
        DependencyHandler deps = project.getDependencies();

        testing.getSuites().register("cliCompat", JvmTestSuite.class, suite -> {
            suite.useJUnitJupiter();

            // Curated base classpath for CLI-compat tests: the rewrite testing
            // harness (which provides ModwRunner + RewriteTest) plus the common
            // Java/Maven assertion helpers, version-aligned through the rewrite BOM.
            // The project dependency supplies the freshly-built recipe classes and
            // their runtime classpath, which ModwRunner forwards to the CLI as the
            // active-recipe classpath.
            //
            // Deliberately a minimal, BOM-pinned set rather than the full unit-test
            // classpath: the recipe's complete implementation closure can drag in
            // snapshot-only transitive deps that don't resolve in isolation. Modules
            // whose fixtures need more (rewrite-gradle, rewrite-yaml, ...) add them
            // to this suite in their own build script via
            // `testing.suites.named("cliCompat")`.
            deps.addProvider("cliCompatImplementation",
                    recipeExt.getRewriteVersion().map(v -> deps.platform("org.openrewrite:rewrite-bom:" + v)));
            deps.add("cliCompatImplementation", "org.openrewrite:rewrite-test");
            deps.add("cliCompatImplementation", "org.openrewrite:rewrite-java");
            deps.add("cliCompatImplementation", "org.openrewrite:rewrite-java-test");
            deps.add("cliCompatImplementation", "org.openrewrite:rewrite-maven");
            deps.add("cliCompatImplementation", project);

            suite.getTargets().all(target -> target.getTestTask().configure(test -> {
                // -PcliCompatVersion=... overrides the version hardcoded in the tests
                // by forwarding into the system property ModwRunner reads. The
                // MODERNE_CLI_REGRESSION_VERSION env var reaches the test JVM
                // automatically and needs no wiring here.
                Object override = project.findProperty("cliCompatVersion");
                if (override != null && !override.toString().trim().isEmpty()) {
                    test.systemProperty("moderne.cli.regression.version", override.toString().trim());
                }
            }));
        });
    }

    private static String capitalize(String str) {
        if (str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
