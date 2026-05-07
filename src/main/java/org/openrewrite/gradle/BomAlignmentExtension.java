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
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * DSL extension for the BOM alignment plugin.
 * <p>
 * The headline feature is {@link #inheritsFrom(String)}, which mirrors every artifact a parent BOM
 * manages as a direct {@code api} dependency on this project. It exists to break the propagation
 * deadlock you hit when a downstream BOM imports an upstream BOM as a {@code platform()}: the
 * {@code platform()} import contributes version constraints to resolution, so the downstream BOM's
 * alignment check is permanently clamped to whatever the upstream BOM's last release said.
 * Enumerating the managed artifacts directly removes that clamp while preserving the superset
 * invariant — every artifact the parent manages is still managed here.
 */
public class BomAlignmentExtension {

    private final Project project;

    public BomAlignmentExtension(Project project) {
        this.project = project;
    }

    /**
     * For every artifact managed by {@code parentBomCoords}, add an {@code api} dependency on this
     * project at the same version selector as {@code parentBomCoords} (typically a dynamic version
     * like {@code latest.release} or {@code latest.integration}). BOMs imported by the parent BOM
     * via {@code <scope>import</scope>} are followed transitively.
     * <p>
     * Use this in place of {@code api(platform("..."))} when you want the downstream BOM to be a
     * superset of the upstream BOM but do not want the upstream's pins to constrain resolution
     * during this project's alignment check.
     */
    public void inheritsFrom(String parentBomCoords) {
        String[] parts = parentBomCoords.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected 'group:artifact:version' coordinates, got: " + parentBomCoords);
        }
        String version = parts[2];
        Set<String> managedDeps = new TreeSet<>();
        collectManagedDepsRecursive(parentBomCoords, managedDeps, new HashSet<>());

        DependencyHandler dependencies = project.getDependencies();
        Configuration api = project.getConfigurations().getByName("api");
        for (String groupArtifact : managedDeps) {
            Dependency dep = (Dependency) dependencies.create(groupArtifact + ":" + version);
            api.getDependencies().add(dep);
        }
    }

    private void collectManagedDepsRecursive(String bomCoords, Set<String> result, Set<String> visited) {
        if (!visited.add(bomCoords)) {
            return;
        }
        File pomFile = fetchPom(bomCoords);
        Document doc = Poms.parse(pomFile);
        if (doc == null) {
            throw new GradleException("Failed to parse BOM POM for " + bomCoords + " at " + pomFile);
        }

        String[] parts = bomCoords.split(":");
        Map<String, String> props = new HashMap<>();
        props.put("project.version", parts[2]);
        props.put("project.groupId", parts[0]);
        props.put("project.artifactId", parts[1]);
        Poms.readProperties(doc, props);

        Element root = doc.getDocumentElement();
        for (Element depMgmt : Poms.directChildElements(root, "dependencyManagement")) {
            for (Element deps : Poms.directChildElements(depMgmt, "dependencies")) {
                for (Element depElem : Poms.directChildElements(deps, "dependency")) {
                    String group = Poms.substitute(Poms.directChildText(depElem, "groupId"), props);
                    String artifact = Poms.substitute(Poms.directChildText(depElem, "artifactId"), props);
                    String depVersion = Poms.substitute(Poms.directChildText(depElem, "version"), props);
                    String scope = Poms.directChildText(depElem, "scope");
                    String type = Poms.directChildText(depElem, "type");
                    if (group == null || artifact == null) {
                        continue;
                    }
                    if ("import".equals(scope) && "pom".equals(type) && depVersion != null) {
                        collectManagedDepsRecursive(group + ":" + artifact + ":" + depVersion, result, visited);
                    } else {
                        result.add(group + ":" + artifact);
                    }
                }
            }
        }
    }

    private File fetchPom(String coords) {
        Dependency dep = (Dependency) project.getDependencies().create(coords + "@pom");
        Configuration cfg = project.getConfigurations().detachedConfiguration(dep);
        cfg.setTransitive(false);
        return cfg.getSingleFile();
    }
}
