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

import lombok.Getter;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedManagedDependency;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
    /**
     * Parent BOMs registered via {@link #inheritsFrom(String)}, keyed by group/artifact with the
     * value being the version Gradle resolved (i.e. the concrete latest release/integration the
     * dynamic selector resolved to, not {@code latest.release} itself). Used by {@code
     * checkBomAlignment} to compare against the versions other consumers pin.
     */
    @Getter
    private final Map<GroupArtifact, String> inheritedBoms = new LinkedHashMap<>();

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
        GroupArtifactVersion gav = parseGav(parentBomCoords);
        FetchedPom resolved = fetchPom(gav);
        // Record resolved version so the alignment check knows the up-to-date version of the parent
        // BOM and can flag downstream consumers that pin an older version of it.
        inheritedBoms.put(gav.asGroupArtifact(), resolved.resolvedVersion());

        // Use MavenParser so parents and transitive <scope>import</scope> entries are flattened for
        // us. ResolvedPom.getDependencyManagement() returns the fully-resolved managed-dep list with
        // placeholders substituted.
        Set<GroupArtifact> managedDeps = new TreeSet<>(GA_BY_TOSTRING);
        for (ResolvedManagedDependency dep : parseResolved(resolved.pomFile(), gradleRepositories()).getPom().getDependencyManagement()) {
            managedDeps.add(new GroupArtifact(dep.getGroupId(), dep.getArtifactId()));
        }

        DependencyHandler dependencies = project.getDependencies();
        Configuration api = project.getConfigurations().getByName("api");
        for (GroupArtifact ga : managedDeps) {
            Dependency dep = dependencies.create(ga.getGroupId() + ":" + ga.getArtifactId() + ":" + gav.getVersion());
            api.getDependencies().add(dep);
        }
    }

    private static MavenResolutionResult parseResolved(File pomFile, List<MavenRepository> repositories) {
        String pomXml;
        try {
            pomXml = new String(Files.readAllBytes(pomFile.toPath()));
        } catch (IOException e) {
            throw new GradleException("Failed to read POM " + pomFile, e);
        }
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(t -> {
            throw new GradleException("Failed to parse POM " + pomFile, t);
        });
        // Tell MavenParser's downloader to look in the project's repositories for parents and
        // imported BOMs, not just the default Maven Central.
        MavenExecutionContextView.view(ctx).setRepositories(repositories);
        SourceFile parsed = MavenParser.builder().build().parse(ctx, pomXml)
                .findFirst()
                .orElseThrow(() -> new GradleException("MavenParser produced no source files for " + pomFile));
        return parsed.getMarkers().findFirst(MavenResolutionResult.class)
                .orElseThrow(() -> new GradleException("MavenParser produced no MavenResolutionResult marker for " + pomFile));
    }

    private List<MavenRepository> gradleRepositories() {
        List<MavenRepository> repos = new ArrayList<>();
        for (ArtifactRepository repo : project.getRepositories()) {
            if (repo instanceof MavenArtifactRepository m) {
                repos.add(new MavenRepository(repo.getName(), m.getUrl().toString(), "true", "true", true, null, null, null, false));
            }
        }
        return repos;
    }

    private FetchedPom fetchPom(GroupArtifactVersion gav) {
        String coords = gav.getGroupId() + ":" + gav.getArtifactId() + ":" + gav.getVersion();
        Dependency dep = project.getDependencies().create(coords + "@pom");
        Configuration cfg = project.getConfigurations().detachedConfiguration(dep);
        cfg.setTransitive(false);
        // Detached configurations don't inherit resolutionStrategy from configurations.all, so the
        // dynamic-version cache TTL the user set on their project configurations doesn't apply.
        // Force an immediate refresh so `latest.release` truly picks up the latest published.
        cfg.getResolutionStrategy().cacheDynamicVersionsFor(0, TimeUnit.SECONDS);
        cfg.getResolutionStrategy().cacheChangingModulesFor(0, TimeUnit.SECONDS);
        Set<ResolvedArtifact> artifacts = cfg.getResolvedConfiguration().getResolvedArtifacts();
        if (artifacts.isEmpty()) {
            throw new GradleException("Could not resolve POM for " + coords);
        }
        ResolvedArtifact ra = artifacts.iterator().next();
        return new FetchedPom(ra.getFile(), ra.getModuleVersion().getId().getVersion());
    }

    private static GroupArtifactVersion parseGav(String coords) {
        String[] parts = coords.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected 'group:artifact:version' coordinates, got: " + coords);
        }
        return new GroupArtifactVersion(parts[0], parts[1], parts[2]);
    }

    /** {@link GroupArtifact} doesn't override compareTo, so build a stable order for tree sets. */
    private static final Comparator<GroupArtifact> GA_BY_TOSTRING =
            Comparator.comparing(GroupArtifact::getGroupId).thenComparing(GroupArtifact::getArtifactId);

    private record FetchedPom(File pomFile, String resolvedVersion) {
    }
}
