/*
 * Copyright 2023 the original author or authors.
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

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.dsl.DependencyHandler;

import javax.inject.Inject;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class RecipeDependenciesExtension {
    private final ConfigurationContainer configurationContainer;
    private final DependencyHandler dependencyHandler;

    private final Map<String, Set<String>> dependenciesBySourceSet = new HashMap<>();

    @Inject
    public RecipeDependenciesExtension(ConfigurationContainer configurationContainer,
                                       DependencyHandler dependencyHandler) {
        this.configurationContainer = configurationContainer;
        this.dependencyHandler = dependencyHandler;
    }

    @SuppressWarnings("unused")
    public void parserClasspath(String dependencyNotation) {
        addDependencyForSourceSet("main", dependencyNotation);
    }

    @SuppressWarnings("unused")
    public void testParserClasspath(String dependencyNotation) {
        addDependencyForSourceSet("test", dependencyNotation);
    }

    /**
     * Add a dependency to the parser classpath for a specific source set.
     * This is useful for custom source sets not covered by the convenience methods above.
     *
     * @param sourceSetName the name of the source set (e.g., "customTest", "functionalTest")
     * @param dependencyNotation the dependency notation (e.g., "org.example:library:1.0.0")
     */
    @SuppressWarnings("unused")
    public void parserClasspath(String sourceSetName, String dependencyNotation) {
        addDependencyForSourceSet(sourceSetName, dependencyNotation);
    }

    void addDependencyForSourceSet(String sourceSetName, String dependencyNotation) {
        dependenciesBySourceSet.computeIfAbsent(sourceSetName, k -> new HashSet<>()).add(dependencyNotation);
    }

    Map<Dependency, File> getResolved() {
        return getResolvedForSourceSet("main");
    }

    Map<Dependency, File> getResolvedForSourceSet(String sourceSetName) {
        Map<Dependency, File> resolved = new HashMap<>();
        Set<String> dependencies = dependenciesBySourceSet.get(sourceSetName);

        if (dependencies == null) {
            return resolved;
        }

        for (String dependencyNotation : dependencies) {
            Dependency dependency = dependencyHandler.create(dependencyNotation);
            if (!(dependency instanceof ExternalModuleDependency)) {
                throw new IllegalArgumentException("Only external module dependencies are supported as recipe dependencies.");
            }
            ((ExternalModuleDependency) dependency).setTransitive(false);
            for (File file : resolve(dependencyNotation, dependency)) {
                resolved.put(dependency, file);
            }
        }

        return resolved;
    }

    /**
     * A dynamic version would otherwise take the highest version across all repositories, which may well be a
     * snapshot in {@code mavenLocal()} or a milestone on Maven Central, and bake it into the type table.
     */
    private Set<File> resolve(String dependencyNotation, Dependency dependency) {
        Configuration configuration = configurationContainer.detachedConfiguration(dependency);
        if (!PreReleaseVersions.isDynamic(dependency.getVersion())) {
            return configuration.resolve();
        }

        Set<String> rejected = new TreeSet<>();
        configuration.getResolutionStrategy().getComponentSelection().all(selection -> {
            String version = selection.getCandidate().getVersion();
            if (PreReleaseVersions.isPreRelease(version)) {
                rejected.add(version);
                selection.reject("pre-release versions are only used when pinned explicitly");
            }
        });
        try {
            return configuration.resolve();
        } catch (ResolveException e) {
            if (rejected.isEmpty()) {
                throw e;
            }
            throw new GradleException("Could not resolve recipe dependency '" + dependencyNotation + "' to a release version. " +
                                      "Pre-release versions " + rejected + " were skipped, as they are only used when pinned explicitly.", e);
        }
    }
}
