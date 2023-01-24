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

import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class RecipeDependenciesExtension {
    private final ConfigurationContainer configurationContainer;
    private final DependencyFactory dependencyFactory;

    private final Set<File> resolved = new HashSet<>();

    @Inject
    public RecipeDependenciesExtension(ConfigurationContainer configurationContainer,
                                       DependencyFactory dependencyFactory) {
        this.configurationContainer = configurationContainer;
        this.dependencyFactory = dependencyFactory;
    }

    public void parserClasspath(String dependencyNotation) {
        Dependency dependency = dependencyFactory.createDependency(dependencyNotation);
        if (!(dependency instanceof ExternalModuleDependency)) {
            throw new IllegalArgumentException("Only external module dependencies are supported as recipe dependencies.");
        }
        ((ExternalModuleDependency) dependency).setTransitive(false);
        resolved.addAll(configurationContainer.detachedConfiguration(dependency).resolve());
    }

    Set<File> getResolved() {
        return resolved;
    }
}
