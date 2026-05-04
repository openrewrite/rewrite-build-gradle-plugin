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

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.tasks.TaskProvider;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@SuppressWarnings("unused")
public class RewriteBomAlignmentPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        Configuration resolveApi = project.getConfigurations().create("resolveApi", c ->
                c.extendsFrom(project.getConfigurations().getByName("api")));

        TaskProvider<?> checkBomAlignment = project.getTasks().register("checkBomAlignment", task -> {
            task.setDescription("Fails if any dependency in the BOM's transitive graph is requested at more than one version.");
            task.setGroup("verification");
            ResolutionResult resolutionResult = resolveApi.getIncoming().getResolutionResult();
            task.doLast(t -> {
                Map<String, Map<String, Set<String>>> requestedVersions = new LinkedHashMap<>();
                for (DependencyResult dep : resolutionResult.getAllDependencies()) {
                    if (!(dep instanceof ResolvedDependencyResult edge)) {
                        continue;
                    }
                    if (!(edge.getRequested() instanceof ModuleComponentSelector selector)) {
                        continue;
                    }
                    String group = selector.getGroup();
                    if (!isManagedGroup(group)) {
                        continue;
                    }
                    String version = selector.getVersion();
                    if (isDynamicVersion(version)) {
                        continue;
                    }
                    String moduleId = group + ":" + selector.getModule();
                    requestedVersions
                            .computeIfAbsent(moduleId, k -> new LinkedHashMap<>())
                            .computeIfAbsent(version, k -> new LinkedHashSet<>())
                            .add(edge.getFrom().getId().getDisplayName());
                }

                Map<String, Map<String, Set<String>>> mismatches = new TreeMap<>();
                for (Map.Entry<String, Map<String, Set<String>>> e : requestedVersions.entrySet()) {
                    if (e.getValue().size() > 1) {
                        mismatches.put(e.getKey(), e.getValue());
                    }
                }
                if (mismatches.isEmpty()) {
                    return;
                }

                StringBuilder message = new StringBuilder("BOM dependency version mismatches detected — align these before publishing:\n");
                for (Map.Entry<String, Map<String, Set<String>>> e : mismatches.entrySet()) {
                    message.append("  ").append(e.getKey()).append('\n');
                    Map<String, Set<String>> sortedByVersion = new TreeMap<>(e.getValue());
                    for (Map.Entry<String, Set<String>> v : sortedByVersion.entrySet()) {
                        message.append("    ").append(v.getKey()).append(" requested by:\n");
                        for (String requester : new TreeSet<>(v.getValue())) {
                            message.append("      ").append(requester).append('\n');
                        }
                    }
                }
                throw new GradleException(message.toString());
            });
        });

        project.getTasks().withType(AbstractPublishToMaven.class).configureEach(t -> t.dependsOn(checkBomAlignment));
        project.getTasks().matching(t -> "check".equals(t.getName())).configureEach(t -> t.dependsOn(checkBomAlignment));
    }

    private static boolean isManagedGroup(String group) {
        return group.startsWith("io.moderne") || group.startsWith("org.openrewrite");
    }

    private static boolean isDynamicVersion(String version) {
        return version.isEmpty() ||
                version.startsWith("latest.") ||
                version.contains("+") ||
                version.contains("[") ||
                version.contains("(");
    }
}
