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
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;
import org.openrewrite.maven.internal.RawPom;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.maven.tree.Version;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.disjoint;

@SuppressWarnings("unused")
public class RewriteBomAlignmentPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        Configuration resolveApi = project.getConfigurations().create("resolveApi", c ->
                c.extendsFrom(project.getConfigurations().getByName("api")));
        DependencyHandler dependencies = project.getDependencies();
        BomAlignmentExtension bomAlignment = dependencies.getExtensions().create("bomAlignment", BomAlignmentExtension.class, project);

        TaskProvider<?> checkBomAlignment = project.getTasks().register("checkBomAlignment", task -> {
            task.setDescription("Fails if any dependency in the BOM's transitive graph is requested at more than one version.");
            task.setGroup("verification");
            ResolutionResult resolutionResult = resolveApi.getIncoming().getResolutionResult();
            task.doLast(t -> {
                Map<GroupArtifact, String> inheritedBoms = bomAlignment.getInheritedBoms();
                PomAnalysis pomAnalysis = analyzePoms(project, resolutionResult, inheritedBoms);
                Map<String, Map<String, Set<String>>> requestedVersions = new LinkedHashMap<>();
                // Each parent BOM declared via bomAlignment.inheritsFrom contributes a pin for itself
                // at its resolved version. This makes the version visible to mismatch detection so
                // any consumer pinning an older version of the parent BOM gets flagged.
                for (Map.Entry<GroupArtifact, String> bom : inheritedBoms.entrySet()) {
                    String moduleId = bom.getKey().getGroupId() + ":" + bom.getKey().getArtifactId();
                    requestedVersions
                            .computeIfAbsent(moduleId, k -> new LinkedHashMap<>())
                            .computeIfAbsent(bom.getValue(), k -> new LinkedHashSet<>())
                            .add(moduleId + ":" + bom.getValue() + " (via inheritsFrom)");
                }
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
                    if (!isManagedRequester(edge.getFrom().getId())) {
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
                // Treat each POM `<dependencyManagement>` entry as a pin contributed by the BOM that
                // declares it. A BOM whose <dependencyManagement> still names an outdated version is
                // itself out of date and needs a re-release before downstream BOMs can be aligned.
                for (Map.Entry<GroupArtifact, Map<GroupArtifact, String>> bom : pomAnalysis.managedDepVersions().entrySet()) {
                    String bomDisplay = displayWithVersion(bom.getKey(), pomAnalysis.visitedArtifactVersions().get(bom.getKey()));
                    for (Map.Entry<GroupArtifact, String> managed : bom.getValue().entrySet()) {
                        requestedVersions
                                .computeIfAbsent(toModuleId(managed.getKey()), k -> new LinkedHashMap<>())
                                .computeIfAbsent(managed.getValue(), k -> new LinkedHashSet<>())
                                .add(bomDisplay);
                    }
                }
                // Each `<scope>import</scope>` entry pins a specific version of the imported BOM. If
                // multiple consumers pin different versions, the importer pinning the older one is
                // out of date and needs a re-release.
                for (Map.Entry<GroupArtifact, Map<GroupArtifact, String>> entry : pomAnalysis.imports().entrySet()) {
                    String requesterDisplay = displayWithVersion(entry.getKey(), pomAnalysis.visitedArtifactVersions().get(entry.getKey()));
                    for (Map.Entry<GroupArtifact, String> imp : entry.getValue().entrySet()) {
                        requestedVersions
                                .computeIfAbsent(toModuleId(imp.getKey()), k -> new LinkedHashMap<>())
                                .computeIfAbsent(imp.getValue(), k -> new LinkedHashSet<>())
                                .add(requesterDisplay);
                    }
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

                Map<String, Map<String, String>> outdatedPinsByRequester = computeOutdatedPinsByRequester(resolutionResult, mismatches, pomAnalysis);
                Set<String> needsRelease = outdatedPinsByRequester.keySet();
                ReleasePlan plan = computeReleasePlan(resolutionResult, pomAnalysis);
                List<Map<String, Set<String>>> waves = plan.waves();
                Map<String, Set<String>> repoDependsOn = plan.repoDependsOn();
                Map<String, String> artifactVersions = plan.artifactVersions();
                // For BOMs registered via inheritsFrom, prefer the latest resolved version over
                // whatever older version some consumer pinned in the resolution graph — this is the
                // version we'd actually want to align everything to.
                for (Map.Entry<GroupArtifact, String> bom : inheritedBoms.entrySet()) {
                    artifactVersions.put(bom.getKey().getGroupId() + ":" + bom.getKey().getArtifactId(), bom.getValue());
                }
                // Mark a repo "blocked" (x) if it needs release and any repo it depends on also needs
                // release — that upstream must ship first. Walk waves in topological order so
                // transitive blocking is captured: a blocked repo also blocks its consumers.
                Set<String> reposNeedingRelease = new java.util.HashSet<>();
                for (Map<String, Set<String>> wave : waves) {
                    for (Map.Entry<String, Set<String>> repo : wave.entrySet()) {
                        if (!disjoint(repo.getValue(), needsRelease)) {
                            reposNeedingRelease.add(repo.getKey());
                        }
                    }
                }
                Set<String> blockedRepos = new java.util.HashSet<>();
                for (Map<String, Set<String>> wave : waves) {
                    for (String repo : wave.keySet()) {
                        if (!reposNeedingRelease.contains(repo)) {
                            continue;
                        }
                        for (String depRepo : repoDependsOn.getOrDefault(repo, Set.of())) {
                            if (reposNeedingRelease.contains(depRepo)) {
                                blockedRepos.add(repo);
                                break;
                            }
                        }
                    }
                }
                Map<String, Set<String>> isolatedRepos = plan.isolatedRepos();
                if (!waves.isEmpty() || !isolatedRepos.isEmpty()) {
                    message.append("\nRelease order to arrive at an aligned BOM (each wave can be released in parallel; ! ready to release, x waiting on a dependency, ✓ already aligned, - relationship-less):\n");
                    for (int i = 0; i < waves.size(); i++) {
                        Map<String, Set<String>> wave = waves.get(i);
                        boolean waveHasReady = false;
                        boolean waveHasBlocked = false;
                        for (String repo : wave.keySet()) {
                            if (!reposNeedingRelease.contains(repo)) {
                                continue;
                            }
                            if (blockedRepos.contains(repo)) {
                                waveHasBlocked = true;
                            } else {
                                waveHasReady = true;
                            }
                        }
                        String waveMarker = waveHasReady ? "! " : waveHasBlocked ? "x " : "✓ ";
                        message.append("  ").append(waveMarker).append("Wave ").append(i + 1).append(":\n");
                        for (Map.Entry<String, Set<String>> repo : wave.entrySet()) {
                            String marker;
                            if (!reposNeedingRelease.contains(repo.getKey())) {
                                marker = "    ✓ ";
                            } else if (blockedRepos.contains(repo.getKey())) {
                                marker = "    x ";
                            } else {
                                marker = "    ! ";
                            }
                            message.append(marker).append(repo.getKey()).append('\n');
                            appendArtifacts(message, repo.getValue(), artifactVersions, outdatedPinsByRequester);
                        }
                    }
                    if (!isolatedRepos.isEmpty()) {
                        message.append("  - Wave 0: These repositories have no apparent dependency relation to any others in this release plan.\n")
                                .append("           These may be fat jars that don't *publish* dependency information discoverable\n")
                                .append("           by this task, but do actually have dependencies.\n");
                        for (Map.Entry<String, Set<String>> repo : isolatedRepos.entrySet()) {
                            message.append("    - ").append(repo.getKey()).append('\n');
                            appendArtifacts(message, repo.getValue(), artifactVersions, outdatedPinsByRequester);
                        }
                    }
                }
                throw new GradleException(message.toString());
            });
        });

        project.getTasks().withType(AbstractPublishToMaven.class).configureEach(t -> t.dependsOn(checkBomAlignment));
        project.getTasks().matching(t -> "check".equals(t.getName())).configureEach(t -> t.dependsOn(checkBomAlignment));
    }

    private static void appendArtifacts(StringBuilder message, Set<String> artifacts, Map<String, String> artifactVersions, Map<String, Map<String, String>> outdatedPinsByRequester) {
        for (String artifact : artifacts) {
            String version = artifactVersions.get(artifact);
            message.append("          ").append(artifact);
            if (version != null) {
                message.append(':').append(version);
            }
            message.append('\n');
            Map<String, String> pins = outdatedPinsByRequester.get(artifact);
            if (pins != null) {
                for (Map.Entry<String, String> pin : pins.entrySet()) {
                    message.append("            requests ").append(pin.getKey()).append(pin.getValue()).append('\n');
                }
            }
        }
    }

    private static boolean isManagedGroup(String group) {
        return group.startsWith("io.moderne") || group.startsWith("org.openrewrite");
    }

    private static boolean isManagedRequester(ComponentIdentifier id) {
        if (id instanceof ProjectComponentIdentifier) {
            return true;
        }
        if (id instanceof ModuleComponentIdentifier moduleId) {
            return isManagedGroup(moduleId.getGroup());
        }
        return false;
    }

    private static Map<String, Map<String, String>> computeOutdatedPinsByRequester(ResolutionResult resolutionResult, Map<String, Map<String, Set<String>>> mismatches, PomAnalysis pomAnalysis) {
        Map<String, String> latestVersionPerModule = new HashMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> e : mismatches.entrySet()) {
            String latest = null;
            for (String version : e.getValue().keySet()) {
                if (latest == null || new Version(version).compareTo(new Version(latest)) > 0) {
                    latest = version;
                }
            }
            if (latest != null) {
                latestVersionPerModule.put(e.getKey(), latest);
            }
        }

        Map<String, Map<String, String>> outdatedByRequester = new TreeMap<>();
        for (DependencyResult dep : resolutionResult.getAllDependencies()) {
            if (!(dep instanceof ResolvedDependencyResult edge)) {
                continue;
            }
            if (!(edge.getRequested() instanceof ModuleComponentSelector selector)) {
                continue;
            }
            String requestedModule = selector.getGroup() + ":" + selector.getModule();
            String latest = latestVersionPerModule.get(requestedModule);
            if (latest == null || latest.equals(selector.getVersion())) {
                continue;
            }
            ComponentIdentifier fromId = edge.getFrom().getId();
            // Skip the BOM project itself — it's what we're publishing, not a separately-released module.
            if (fromId instanceof ModuleComponentIdentifier from && isManagedGroup(from.getGroup())) {
                String requester = from.getGroup() + ":" + from.getModule();
                String pinned = ":" + selector.getVersion() + " (latest " + latest + ")";
                outdatedByRequester.computeIfAbsent(requester, k -> new TreeMap<>()).put(requestedModule, pinned);
            }
        }
        // Also flag BOMs whose <dependencyManagement> entries pin an outdated version. The BOM is
        // itself the requester here — its release is what publishes a refreshed dependencyManagement.
        for (Map.Entry<GroupArtifact, Map<GroupArtifact, String>> bom : pomAnalysis.managedDepVersions().entrySet()) {
            String requester = toModuleId(bom.getKey());
            for (Map.Entry<GroupArtifact, String> managed : bom.getValue().entrySet()) {
                String requestedModule = toModuleId(managed.getKey());
                String version = managed.getValue();
                String latest = latestVersionPerModule.get(requestedModule);
                if (latest == null || latest.equals(version)) {
                    continue;
                }
                String pinned = ":" + version + " (latest " + latest + ")";
                outdatedByRequester.computeIfAbsent(requester, k -> new TreeMap<>()).put(requestedModule, pinned);
            }
        }
        // And flag artifacts whose POM imports an outdated version of a BOM via <scope>import</scope>.
        for (Map.Entry<GroupArtifact, Map<GroupArtifact, String>> entry : pomAnalysis.imports().entrySet()) {
            String requester = toModuleId(entry.getKey());
            for (Map.Entry<GroupArtifact, String> imp : entry.getValue().entrySet()) {
                String requestedModule = toModuleId(imp.getKey());
                String version = imp.getValue();
                String latest = latestVersionPerModule.get(requestedModule);
                if (latest == null || latest.equals(version)) {
                    continue;
                }
                String pinned = ":" + version + " (latest " + latest + ")";
                outdatedByRequester.computeIfAbsent(requester, k -> new TreeMap<>()).put(requestedModule, pinned);
            }
        }
        return outdatedByRequester;
    }

    private static String toModuleId(GroupArtifact ga) {
        return ga.getGroupId() + ":" + ga.getArtifactId();
    }

    private static String displayWithVersion(GroupArtifact ga, @Nullable String version) {
        return version != null ? ga.getGroupId() + ":" + ga.getArtifactId() + ":" + version :
                ga.getGroupId() + ":" + ga.getArtifactId();
    }

    private record ReleasePlan(List<Map<String, Set<String>>> waves, Map<String, Set<String>> repoDependsOn, Map<String, String> artifactVersions, Map<String, Set<String>> isolatedRepos) {
    }

    private record PomAnalysis(
            Map<GroupArtifact, String> scmUrls,
            Map<GroupArtifact, Map<GroupArtifact, String>> imports,
            Map<GroupArtifact, String> importedBomVersions,
            Map<GroupArtifact, Map<GroupArtifact, String>> managedDepVersions,
            Map<GroupArtifact, String> visitedArtifactVersions) {
    }

    private static ReleasePlan computeReleasePlan(ResolutionResult resolutionResult, PomAnalysis pomAnalysis) {
        Map<String, Set<String>> artifactDependsOn = new TreeMap<>();
        Set<String> artifactNodes = new TreeSet<>();
        Map<String, String> artifactVersions = new HashMap<>();
        for (DependencyResult dep : resolutionResult.getAllDependencies()) {
            if (!(dep instanceof ResolvedDependencyResult edge)) {
                continue;
            }
            if (!(edge.getRequested() instanceof ModuleComponentSelector selector)) {
                continue;
            }
            if (!isManagedGroup(selector.getGroup())) {
                continue;
            }
            String to = selector.getGroup() + ":" + selector.getModule();
            artifactNodes.add(to);
            artifactDependsOn.computeIfAbsent(to, k -> new TreeSet<>());
            if (edge.getSelected().getId() instanceof ModuleComponentIdentifier selectedId) {
                artifactVersions.put(to, selectedId.getVersion());
            }

            ComponentIdentifier fromId = edge.getFrom().getId();
            if (!(fromId instanceof ModuleComponentIdentifier fromModule)) {
                continue;
            }
            if (!isManagedGroup(fromModule.getGroup())) {
                continue;
            }
            String from = fromModule.getGroup() + ":" + fromModule.getModule();
            artifactNodes.add(from);
            artifactVersions.put(from, fromModule.getVersion());
            artifactDependsOn.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
        }

        // Add edges for `<scope>import</scope>` dependencies declared in artifact POMs (e.g. metromap
        // depending on rewrite-recipe-bom via platform import). Gradle's resolution graph treats these
        // as version constraints only and does not surface them as edges, but for release ordering they
        // matter — the imported BOM must ship before its consumer can pick up new pins.
        for (Map.Entry<GroupArtifact, Map<GroupArtifact, String>> entry : pomAnalysis.imports().entrySet()) {
            String fromArtifact = toModuleId(entry.getKey());
            if (!artifactNodes.contains(fromArtifact)) {
                continue;
            }
            for (GroupArtifact importedBom : entry.getValue().keySet()) {
                String importedKey = toModuleId(importedBom);
                artifactNodes.add(importedKey);
                artifactDependsOn.computeIfAbsent(importedKey, k -> new TreeSet<>());
                artifactDependsOn.computeIfAbsent(fromArtifact, k -> new TreeSet<>()).add(importedKey);
                String importedVersion = pomAnalysis.importedBomVersions().get(importedBom);
                if (importedVersion != null) {
                    artifactVersions.putIfAbsent(importedKey, importedVersion);
                }
            }
        }

        // Add edges for non-import `<dependencyManagement>` entries: a BOM that pins managed artifacts
        // must ship after those artifacts so its new release can point at their new versions.
        // Only add edges to artifacts already in the graph — managed entries pointing at unrelated
        // modules don't matter for this BOM's release ordering.
        for (Map.Entry<GroupArtifact, Map<GroupArtifact, String>> entry : pomAnalysis.managedDepVersions().entrySet()) {
            String managingArtifact = toModuleId(entry.getKey());
            if (!artifactNodes.contains(managingArtifact)) {
                continue;
            }
            for (GroupArtifact managedGa : entry.getValue().keySet()) {
                String managedArtifact = toModuleId(managedGa);
                if (!artifactNodes.contains(managedArtifact)) {
                    continue;
                }
                if (managedArtifact.equals(managingArtifact)) {
                    continue;
                }
                artifactDependsOn.computeIfAbsent(managingArtifact, k -> new TreeSet<>()).add(managedArtifact);
            }
        }

        // Collapse to repo-level: artifacts sharing an SCM URL are released together.
        // Artifacts with no known SCM URL get their own bucket keyed by group:artifact.
        Map<GroupArtifact, String> scmUrls = pomAnalysis.scmUrls();
        Map<String, String> artifactToRepo = new HashMap<>();
        Map<String, Set<String>> repoArtifacts = new TreeMap<>();
        for (String artifact : artifactNodes) {
            String[] ga = artifact.split(":");
            String scm = ga.length == 2 ? scmUrls.get(new GroupArtifact(ga[0], ga[1])) : null;
            String repo = repoLabel(artifact, scm);
            artifactToRepo.put(artifact, repo);
            repoArtifacts.computeIfAbsent(repo, k -> new TreeSet<>()).add(artifact);
        }

        Map<String, Set<String>> repoDependsOn = new TreeMap<>();
        for (String repo : repoArtifacts.keySet()) {
            repoDependsOn.put(repo, new TreeSet<>());
        }
        for (Map.Entry<String, Set<String>> e : artifactDependsOn.entrySet()) {
            String fromRepo = artifactToRepo.get(e.getKey());
            for (String to : e.getValue()) {
                String toRepo = artifactToRepo.get(to);
                if (!fromRepo.equals(toRepo)) {
                    repoDependsOn.get(fromRepo).add(toRepo);
                }
            }
        }

        // Defer "isolated" repos (no incoming or outgoing edges to other repos in the plan) to the
        // final wave instead of letting them surface in Wave 1. Their POMs typically declare no
        // managed deps (e.g. fatjar artifacts whose POMs don't reflect actual runtime deps), so
        // there's nothing to schedule them against. Putting them last is safer than first — if their
        // independence is real, it doesn't matter; if it's a false positive from an inaccurate POM,
        // they'll at least ship after any consumers they secretly depend on.
        Set<String> incomingRepos = new HashSet<>();
        for (Set<String> deps : repoDependsOn.values()) {
            incomingRepos.addAll(deps);
        }
        Set<String> isolatedRepos = new TreeSet<>();
        for (String repo : repoArtifacts.keySet()) {
            boolean hasOutgoing = !repoDependsOn.getOrDefault(repo, Set.of()).isEmpty();
            boolean hasIncoming = incomingRepos.contains(repo);
            if (!hasOutgoing && !hasIncoming) {
                isolatedRepos.add(repo);
            }
        }

        List<Map<String, Set<String>>> waves = new ArrayList<>();
        Set<String> remaining = new TreeSet<>(repoArtifacts.keySet());
        remaining.removeAll(isolatedRepos);
        while (!remaining.isEmpty()) {
            Set<String> wave = new TreeSet<>();
            for (String repo : remaining) {
                boolean ready = true;
                for (String d : repoDependsOn.getOrDefault(repo, Set.of())) {
                    if (remaining.contains(d)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    wave.add(repo);
                }
            }
            if (wave.isEmpty()) {
                wave.addAll(remaining);
            }
            Map<String, Set<String>> repoToArtifacts = new TreeMap<>();
            for (String repo : wave) {
                repoToArtifacts.put(repo, repoArtifacts.get(repo));
            }
            waves.add(repoToArtifacts);
            remaining.removeAll(wave);
        }

        Map<String, Set<String>> isolatedRepoArtifacts = new TreeMap<>();
        for (String repo : isolatedRepos) {
            isolatedRepoArtifacts.put(repo, repoArtifacts.get(repo));
        }
        return new ReleasePlan(waves, repoDependsOn, artifactVersions, isolatedRepoArtifacts);
    }

    private static String repoLabel(String artifact, @Nullable String scmUrl) {
        String releases = toReleasesUrl(scmUrl);
        if (releases != null) {
            return releases;
        }
        return artifact + " (scm url unknown)";
    }

    private static PomAnalysis analyzePoms(Project project, ResolutionResult resolutionResult, Map<GroupArtifact, String> inheritedBoms) {
        Map<GroupArtifact, String> scmUrls = new HashMap<>();
        Map<GroupArtifact, Map<GroupArtifact, String>> imports = new HashMap<>();
        Map<GroupArtifact, String> importedBomVersions = new HashMap<>();
        Map<GroupArtifact, Map<GroupArtifact, String>> managedDepVersions = new HashMap<>();
        Map<GroupArtifact, String> visitedArtifactVersions = new HashMap<>();
        Set<GroupArtifact> processed = new HashSet<>();
        Deque<GroupArtifactVersion> queue = new ArrayDeque<>();

        for (DependencyResult dep : resolutionResult.getAllDependencies()) {
            if (!(dep instanceof ResolvedDependencyResult edge)) {
                continue;
            }
            ComponentIdentifier id = edge.getSelected().getId();
            if (id instanceof ModuleComponentIdentifier moduleId && isManagedGroup(moduleId.getGroup())) {
                queue.add(new GroupArtifactVersion(moduleId.getGroup(), moduleId.getModule(), moduleId.getVersion()));
            }
        }
        // Seed inherited BOMs at the version inheritsFrom resolved them to. This ensures we walk
        // their POMs (so their managed entries become pins) even though they aren't in the
        // resolution graph as dependencies.
        for (Map.Entry<GroupArtifact, String> bom : inheritedBoms.entrySet()) {
            queue.add(new GroupArtifactVersion(bom.getKey().getGroupId(), bom.getKey().getArtifactId(), bom.getValue()));
        }

        while (!queue.isEmpty()) {
            GroupArtifactVersion coords = queue.poll();
            GroupArtifact ga = coords.asGroupArtifact();
            if (!processed.add(ga)) {
                continue;
            }
            visitedArtifactVersions.put(ga, coords.getVersion());
            File pomFile;
            try {
                pomFile = fetchPomFile(project, coords);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (pomFile == null) {
                continue;
            }
            byte[] pomBytes;
            try {
                pomBytes = Files.readAllBytes(pomFile.toPath());
            } catch (IOException ignored) {
                continue;
            }

            String scm = parseScmUrl(new String(pomBytes, StandardCharsets.UTF_8));
            if (scm != null) {
                scmUrls.put(ga, scm);
            }

            RawPom rawPom;
            try {
                rawPom = RawPom.parse(new ByteArrayInputStream(pomBytes), null);
            } catch (RuntimeException ignored) {
                continue;
            }

            Map<String, String> props = new HashMap<>();
            if (rawPom.getProperties() != null) {
                props.putAll(rawPom.getProperties());
            }
            props.put("project.version", coords.getVersion());
            props.put("project.groupId", coords.getGroupId());
            props.put("project.artifactId", coords.getArtifactId());

            Map<GroupArtifact, String> myImports = new TreeMap<>(GA_BY_GROUP_ARTIFACT);
            Map<GroupArtifact, String> myManagedDeps = new TreeMap<>(GA_BY_GROUP_ARTIFACT);
            RawPom.DependencyManagement depMgmt = rawPom.getDependencyManagement();
            if (depMgmt != null && depMgmt.getDependencies() != null) {
                for (RawPom.Dependency dep : depMgmt.getDependencies().getDependencies()) {
                    String group = ResolvedPom.placeholderHelper.replacePlaceholders(dep.getGroupId(), props::get);
                    String artifact = ResolvedPom.placeholderHelper.replacePlaceholders(dep.getArtifactId(), props::get);
                    String version = dep.getVersion() == null ? null : ResolvedPom.placeholderHelper.replacePlaceholders(dep.getVersion(), props::get);
                    if (group == null || artifact == null) {
                        continue;
                    }
                    if (!isManagedGroup(group)) {
                        continue;
                    }
                    GroupArtifact importKey = new GroupArtifact(group, artifact);
                    if ("import".equals(dep.getScope()) && "pom".equals(dep.getType()) && version != null) {
                        myImports.put(importKey, version);
                        importedBomVersions.putIfAbsent(importKey, version);
                        if (!processed.contains(importKey)) {
                            queue.add(new GroupArtifactVersion(group, artifact, version));
                        }
                    } else if (version != null && !isDynamicVersion(version)) {
                        myManagedDeps.put(importKey, version);
                    }
                }
            }
            if (!myImports.isEmpty()) {
                imports.put(ga, myImports);
            }
            if (!myManagedDeps.isEmpty()) {
                managedDepVersions.put(ga, myManagedDeps);
            }
        }

        return new PomAnalysis(scmUrls, imports, importedBomVersions, managedDepVersions, visitedArtifactVersions);
    }

    /** {@link GroupArtifact} doesn't override compareTo, so we provide a stable order for tree sets/maps. */
    private static final java.util.Comparator<GroupArtifact> GA_BY_GROUP_ARTIFACT =
            java.util.Comparator.comparing(GroupArtifact::getGroupId).thenComparing(GroupArtifact::getArtifactId);

    private static @Nullable File fetchPomFile(Project project, GroupArtifactVersion gav) {
        try {
            String coords = gav.getGroupId() + ":" + gav.getArtifactId() + ":" + gav.getVersion();
            Dependency dep = project.getDependencies().create(coords + "@pom");
            Configuration cfg = project.getConfigurations().detachedConfiguration(dep);
            cfg.setTransitive(false);
            return cfg.getSingleFile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Pattern SCM_URL_PATTERN = Pattern.compile("<scm[^>]*>[\\s\\S]*?<url[^>]*>([^<]+)</url>[\\s\\S]*?</scm>");

    private static @Nullable String parseScmUrl(String pomContent) {
        Matcher m = SCM_URL_PATTERN.matcher(pomContent);
        if (!m.find()) {
            return null;
        }
        String url = m.group(1).trim();
        return url.isEmpty() ? null : url;
    }

    private static @Nullable String toReleasesUrl(@Nullable String scmUrl) {
        if (scmUrl == null || scmUrl.isEmpty()) {
            return null;
        }
        String url = scmUrl.trim();
        if (url.startsWith("scm:git:")) {
            url = url.substring("scm:git:".length());
        } else if (url.startsWith("scm:")) {
            int next = url.indexOf(':', 4);
            if (next > 0) {
                url = url.substring(next + 1);
            }
        }
        if (url.startsWith("git@github.com:")) {
            url = "https://github.com/" + url.substring("git@github.com:".length());
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.contains("github.com/")) {
            return url + "/releases";
        }
        return url;
    }

    private static boolean isDynamicVersion(String version) {
        return version.isEmpty() ||
                version.startsWith("latest.") ||
                version.contains("+") ||
                version.contains("[") ||
                version.contains("(");
    }
}
