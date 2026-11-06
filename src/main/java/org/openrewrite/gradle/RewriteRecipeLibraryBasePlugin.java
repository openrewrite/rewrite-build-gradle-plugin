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
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.testing.base.TestingExtension;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.openrewrite.internal.StringUtils.capitalize;

@SuppressWarnings("UnstableApiUsage")
public class RewriteRecipeLibraryBasePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RewriteRecipeLibraryExtension ext = project.getExtensions().create("rewriteRecipe", RewriteRecipeLibraryExtension.class);
        ext.getRewriteVersion().convention(project.hasProperty("releasing") ?
                "latest.release" : "latest.integration");

        project.getPlugins().apply(RewriteJavaPlugin.class);
        project.getPlugins().apply(RewriteRecipeMarketplacePlugin.class);

        RecipeDependenciesExtension recipeDependencies = project.getExtensions().create("recipeDependencies", RecipeDependenciesExtension.class);

        // Register base tasks for backward compatibility
        project.getTasks().register("createTypeTable", RecipeDependenciesTypeTableTask.class, task -> {
            task.getSourceSetName().convention("main");
            RecipeDependenciesExtension extension = project.getExtensions().getByType(RecipeDependenciesExtension.class);
            task.getRecipeDependenciesClasspath().from(
                project.provider(() -> extension.getResolvedForSourceSet("main").values())
            );
        });
        project.getTasks().register("downloadRecipeDependencies", RecipeDependenciesDownloadTask.class, task -> {
            task.getResourceDirectories().from(project.getExtensions().getByType(JavaPluginExtension.class)
                    .getSourceSets().getByName("main").getResources().getSourceDirectories());
            task.getRecipeDependencies().set(project.provider(recipeDependencies::getResolved));
        });

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
            addRewriteJava25Parser(project);
            handTestsTheToolingPlugin(project);
        });
    }

    /**
     * A test that calls rewrite's {@code withToolingApi()} starts a Gradle build of its own, which loads the tooling
     * plugin at the model's version. This build resolves that plugin consistently with the tests' classpath, and the
     * test hands the plugin's files to the build it starts.
     */
    private static void handTestsTheToolingPlugin(Project project) {
        Configuration testRuntimeClasspath = project.getConfigurations()
                .getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        Configuration toolingPlugin = project.getConfigurations().create("rewriteToolingPlugin", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.shouldResolveConsistentlyWith(testRuntimeClasspath);
            configuration.attributes(attributes -> {
                AttributeContainer testAttributes = testRuntimeClasspath.getAttributes();
                testAttributes.keySet().forEach(key -> copyAttribute(project, key, testAttributes, attributes));
            });
            configuration.withDependencies(dependencies -> toolingModelVersion(testRuntimeClasspath).ifPresent(version ->
                    dependencies.add(project.getDependencies().create("org.openrewrite.gradle.tooling:plugin:" + version))));
        });
        project.getTasks().named(JavaPlugin.TEST_TASK_NAME, Test.class, test ->
                test.getJvmArgumentProviders().add(new ToolingPluginClasspath(toolingPlugin,
                        project.getLayout().getBuildDirectory().file("tmp/" + test.getName() + "/tooling-plugin-classpath.txt"))));
    }

    private static Optional<String> toolingModelVersion(Configuration testRuntimeClasspath) {
        return testRuntimeClasspath.getIncoming().getResolutionResult().getAllComponents().stream()
                .map(ResolvedComponentResult::getId)
                .filter(ModuleComponentIdentifier.class::isInstance)
                .map(ModuleComponentIdentifier.class::cast)
                .filter(id -> "org.openrewrite.gradle.tooling".equals(id.getGroup()) && "model".equals(id.getModule()))
                .map(ModuleComponentIdentifier::getVersion)
                // A lock pins a snapshot by its timestamp, which the plugin's snapshot need not share. Asked for by
                // its base version, the plugin gets the lock's own pin.
                .map(version -> version.replaceFirst("-\\d{8}\\.\\d{6}-\\d+$", "-SNAPSHOT"))
                .findFirst();
    }

    // Read lazily: the target JVM version attribute comes from the toolchain, and reading it while the plugin is
    // applied finalizes java.toolchain before the build script can set it
    private static <T> void copyAttribute(Project project, Attribute<T> key, AttributeContainer from, AttributeContainer to) {
        to.attributeProvider(key, project.provider(() -> from.getAttribute(key)));
    }

    /**
     * Passes the tooling plugin's files to the test JVM in the manifest that rewrite's {@code OpenRewriteModelBuilder}
     * reads when {@code org.openrewrite.gradle.local.use-embedded-classpath} names one.
     */
    static class ToolingPluginClasspath implements CommandLineArgumentProvider {
        private final FileCollection classpath;
        private final Provider<RegularFile> manifest;

        ToolingPluginClasspath(FileCollection classpath, Provider<RegularFile> manifest) {
            this.classpath = classpath;
            this.manifest = manifest;
        }

        @Classpath
        public FileCollection getClasspath() {
            return classpath;
        }

        // It lists absolute paths, so the build cache keys on the classpath above
        @Internal
        public Provider<RegularFile> getManifest() {
            return manifest;
        }

        @Override
        public Iterable<String> asArguments() {
            Set<File> files = classpath.getFiles();
            if (files.isEmpty()) {
                return List.of();
            }
            Path path = manifest.get().getAsFile().toPath();
            try {
                Files.createDirectories(path.getParent());
                Files.write(path, files.stream().map(File::getAbsolutePath).toList());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return List.of("-Dorg.openrewrite.gradle.local.use-embedded-classpath=" + path);
        }
    }

    /**
     * Tests run on the Java 25 toolchain, where {@code JavaParser.fromJavaVersion()} would otherwise fall back to
     * an older parser running against JDK 25's compiler. Added without a version, so only to projects whose tests
     * already align {@code org.openrewrite} versions through a BOM.
     */
    private static void addRewriteJava25Parser(Project project) {
        project.getConfigurations().named(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME, testRuntimeOnly ->
                testRuntimeOnly.withDependencies(dependencies -> {
                    boolean rewriteBom = project.getConfigurations()
                            .getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                            .getAllDependencies().stream()
                            .anyMatch(RewriteRecipeLibraryBasePlugin::isRewritePlatform);
                    if (rewriteBom) {
                        dependencies.add(project.getDependencies().create("org.openrewrite:rewrite-java-25"));
                    }
                }));
    }

    private static boolean isRewritePlatform(Dependency dependency) {
        if (!(dependency instanceof ModuleDependency module) || module.getGroup() == null ||
            !(module.getGroup().startsWith("org.openrewrite") || module.getGroup().startsWith("io.moderne"))) {
            return false;
        }
        Category category = module.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && (Category.REGULAR_PLATFORM.equals(category.getName()) ||
                                    Category.ENFORCED_PLATFORM.equals(category.getName()));
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
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
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
            deps.add("cliCompatImplementation", deps.project(Map.of("path", project.getPath())));

            suite.getTargets().all(target -> target.getTestTask().configure(test -> {
                test.getJavaLauncher().set(toolchains.launcherFor(spec ->
                        spec.getLanguageVersion().set(JavaLanguageVersion.of(25))));

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
}
