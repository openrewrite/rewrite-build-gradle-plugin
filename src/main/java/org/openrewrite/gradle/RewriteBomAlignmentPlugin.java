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
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        DependencyHandler dependencies = project.getDependencies();
        (dependencies).getExtensions().create("bomAlignment", BomAlignmentExtension.class, project);

        TaskProvider<?> checkBomAlignment = project.getTasks().register("checkBomAlignment", task -> {
            task.setDescription("Fails if any dependency in the BOM's transitive graph is requested at more than one version.");
            task.setGroup("verification");
            ResolutionResult resolutionResult = resolveApi.getIncoming().getResolutionResult();
            task.doLast(t -> {
                PomAnalysis pomAnalysis = analyzePoms(project, resolutionResult);
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
                for (Map.Entry<String, Map<String, String>> bom : pomAnalysis.managedDepVersions().entrySet()) {
                    String bomKey = bom.getKey();
                    String bomVersion = pomAnalysis.visitedArtifactVersions().get(bomKey);
                    String bomDisplay = bomVersion != null ? bomKey + ":" + bomVersion : bomKey;
                    for (Map.Entry<String, String> managed : bom.getValue().entrySet()) {
                        requestedVersions
                                .computeIfAbsent(managed.getKey(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(managed.getValue(), k -> new LinkedHashSet<>())
                                .add(bomDisplay);
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
                // Mark a repo "blocked" (x) if it needs release and any repo it depends on also needs
                // release — that upstream must ship first. Walk waves in topological order so
                // transitive blocking is captured: a blocked repo also blocks its consumers.
                Set<String> reposNeedingRelease = new java.util.HashSet<>();
                for (Map<String, Set<String>> wave : waves) {
                    for (Map.Entry<String, Set<String>> repo : wave.entrySet()) {
                        if (!Collections.disjoint(repo.getValue(), needsRelease)) {
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
                if (!waves.isEmpty()) {
                    message.append("\nRelease order to arrive at an aligned BOM (each wave can be released in parallel; ! ready to release, x waiting on a dependency, ✓ already aligned):\n");
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
                            for (String artifact : repo.getValue()) {
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
                if (latest == null || compareVersions(version, latest) > 0) {
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
        for (Map.Entry<String, Map<String, String>> bom : pomAnalysis.managedDepVersions().entrySet()) {
            String requester = bom.getKey();
            for (Map.Entry<String, String> managed : bom.getValue().entrySet()) {
                String requestedModule = managed.getKey();
                String version = managed.getValue();
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

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.min(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int cmp = compareVersionPart(pa[i], pb[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static int compareVersionPart(String a, String b) {
        Integer ai = tryParseInt(a);
        Integer bi = tryParseInt(b);
        if (ai != null && bi != null) {
            return Integer.compare(ai, bi);
        }
        if (ai != null) {
            return 1;
        }
        if (bi != null) {
            return -1;
        }
        return a.compareTo(b);
    }

    private static @Nullable Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record ReleasePlan(List<Map<String, Set<String>>> waves, Map<String, Set<String>> repoDependsOn, Map<String, String> artifactVersions) {
    }

    private record PomAnalysis(
            Map<String, String> scmUrls,
            Map<String, Set<String>> imports,
            Map<String, String> importedBomVersions,
            Map<String, Map<String, String>> managedDepVersions,
            Map<String, String> visitedArtifactVersions) {
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
        for (Map.Entry<String, Set<String>> entry : pomAnalysis.imports().entrySet()) {
            String fromArtifact = entry.getKey();
            if (!artifactNodes.contains(fromArtifact)) {
                continue;
            }
            for (String importedBom : entry.getValue()) {
                artifactNodes.add(importedBom);
                artifactDependsOn.computeIfAbsent(importedBom, k -> new TreeSet<>());
                artifactDependsOn.computeIfAbsent(fromArtifact, k -> new TreeSet<>()).add(importedBom);
                String importedVersion = pomAnalysis.importedBomVersions().get(importedBom);
                if (importedVersion != null) {
                    artifactVersions.putIfAbsent(importedBom, importedVersion);
                }
            }
        }

        // Add edges for non-import `<dependencyManagement>` entries: a BOM that pins managed artifacts
        // must ship after those artifacts so its new release can point at their new versions.
        // Only add edges to artifacts already in the graph — managed entries pointing at unrelated
        // modules don't matter for this BOM's release ordering.
        for (Map.Entry<String, Map<String, String>> entry : pomAnalysis.managedDepVersions().entrySet()) {
            String managingArtifact = entry.getKey();
            if (!artifactNodes.contains(managingArtifact)) {
                continue;
            }
            for (String managedArtifact : entry.getValue().keySet()) {
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
        Map<String, String> scmUrls = pomAnalysis.scmUrls();
        Map<String, String> artifactToRepo = new HashMap<>();
        Map<String, Set<String>> repoArtifacts = new TreeMap<>();
        for (String artifact : artifactNodes) {
            String repo = repoLabel(artifact, scmUrls.get(artifact));
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

        if (!isolatedRepos.isEmpty()) {
            Map<String, Set<String>> finalWave;
            if (waves.isEmpty()) {
                finalWave = new TreeMap<>();
                waves.add(finalWave);
            } else {
                finalWave = waves.get(waves.size() - 1);
            }
            for (String repo : isolatedRepos) {
                finalWave.put(repo, repoArtifacts.get(repo));
            }
        }

        return new ReleasePlan(waves, repoDependsOn, artifactVersions);
    }

    private static String repoLabel(String artifact, @Nullable String scmUrl) {
        String releases = toReleasesUrl(scmUrl);
        if (releases != null) {
            return releases;
        }
        return artifact + " (scm url unknown)";
    }

    private static PomAnalysis analyzePoms(Project project, ResolutionResult resolutionResult) {
        Map<String, String> scmUrls = new HashMap<>();
        Map<String, Set<String>> imports = new HashMap<>();
        Map<String, String> importedBomVersions = new HashMap<>();
        Map<String, Map<String, String>> managedDepVersions = new HashMap<>();
        Map<String, String> visitedArtifactVersions = new HashMap<>();
        Set<String> processed = new HashSet<>();
        java.util.Deque<String[]> queue = new java.util.ArrayDeque<>();

        for (DependencyResult dep : resolutionResult.getAllDependencies()) {
            if (!(dep instanceof ResolvedDependencyResult edge)) {
                continue;
            }
            ComponentIdentifier id = edge.getSelected().getId();
            if (id instanceof ModuleComponentIdentifier moduleId && isManagedGroup(moduleId.getGroup())) {
                queue.add(new String[]{moduleId.getGroup(), moduleId.getModule(), moduleId.getVersion()});
            }
        }

        while (!queue.isEmpty()) {
            String[] coords = queue.poll();
            String key = coords[0] + ":" + coords[1];
            if (!processed.add(key)) {
                continue;
            }
            visitedArtifactVersions.put(key, coords[2]);
            File pomFile;
            try {
                pomFile = fetchPomFile(project, coords[0] + ":" + coords[1] + ":" + coords[2]);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (pomFile == null) {
                continue;
            }
            Document doc = Poms.parse(pomFile);
            if (doc == null) {
                continue;
            }

            String scm = parseScmUrl(doc);
            if (scm != null) {
                scmUrls.put(key, scm);
            }

            Map<String, String> props = new HashMap<>();
            props.put("project.version", coords[2]);
            props.put("project.groupId", coords[0]);
            props.put("project.artifactId", coords[1]);
            Poms.readProperties(doc, props);

            Set<String> myImports = new TreeSet<>();
            Map<String, String> myManagedDeps = new TreeMap<>();
            Element root = doc.getDocumentElement();
            for (Element depMgmt : Poms.directChildElements(root, "dependencyManagement")) {
                for (Element deps : Poms.directChildElements(depMgmt, "dependencies")) {
                    for (Element depElem : Poms.directChildElements(deps, "dependency")) {
                        String group = Poms.substitute(Poms.directChildText(depElem, "groupId"), props);
                        String artifact = Poms.substitute(Poms.directChildText(depElem, "artifactId"), props);
                        String version = Poms.substitute(Poms.directChildText(depElem, "version"), props);
                        String scope = Poms.directChildText(depElem, "scope");
                        String type = Poms.directChildText(depElem, "type");
                        if (group == null || artifact == null) {
                            continue;
                        }
                        if (!isManagedGroup(group)) {
                            continue;
                        }
                        if ("import".equals(scope) && "pom".equals(type) && version != null) {
                            String importKey = group + ":" + artifact;
                            myImports.add(importKey);
                            importedBomVersions.putIfAbsent(importKey, version);
                            if (!processed.contains(importKey)) {
                                queue.add(new String[]{group, artifact, version});
                            }
                        } else if (version != null && !isDynamicVersion(version)) {
                            myManagedDeps.put(group + ":" + artifact, version);
                        }
                    }
                }
            }
            if (!myImports.isEmpty()) {
                imports.put(key, myImports);
            }
            if (!myManagedDeps.isEmpty()) {
                managedDepVersions.put(key, myManagedDeps);
            }
        }

        return new PomAnalysis(scmUrls, imports, importedBomVersions, managedDepVersions, visitedArtifactVersions);
    }

    private static @Nullable File fetchPomFile(Project project, String coords) {
        try {
            Dependency dep = (Dependency) project.getDependencies().create(coords + "@pom");
            Configuration cfg = project.getConfigurations().detachedConfiguration(dep);
            cfg.setTransitive(false);
            return cfg.getSingleFile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String parseScmUrl(Document doc) {
        Element root = doc.getDocumentElement();
        for (Element scmEl : Poms.directChildElements(root, "scm")) {
            String url = Poms.directChildText(scmEl, "url");
            if (url != null && !url.isEmpty()) {
                return url;
            }
        }
        return null;
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
