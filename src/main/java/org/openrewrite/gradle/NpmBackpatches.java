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

import com.fasterxml.jackson.databind.JsonNode;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Tar;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The Moderne Backpatch Alliance's npm backpatches that a package's production dependencies take, bundled into the
 * package, so that whoever installs it gets them from the package itself. The alliance serves its backpatches only to
 * its customers, and npm has no way for a package to name where its dependencies come from, so bundling is how a
 * backpatch reaches the package's installers.
 * <ul>
 * <li>Which backpatch fixes what is the alliance's npm index: each package's fixed upstream versions, each with its
 * newest backpatch.</li>
 * <li>An installed version takes a backpatch on its own line only: the same major version, and the same minor for a
 * 0.x, fixing an upstream version at or after the installed one.</li>
 * <li>A direct dependency is bundled when anything in its part of the production tree takes a backpatch, since an
 * install resolves what a bundled package requires from inside the bundle. Our own packages, {@code @openrewrite} and
 * {@code @moderne}, are never bundled: each bundles its own backpatches when it is packed.</li>
 * </ul>
 */
final class NpmBackpatches {

    static final List<String> OWN_SCOPES = List.of("@openrewrite/", "@moderne/");

    private static final String MARKER = "-backpatch-";
    private static final String NODE_MODULES = "node_modules/";

    private NpmBackpatches() {
    }

    /**
     * What to change in a staging copy of the package to install its production dependencies with their backpatches.
     *
     * @param direct    each direct dependency that takes a backpatch itself, with the backpatch's version
     * @param overrides each install that takes one, as an npm override selector ({@code name@installed}), with the
     *                  backpatch's version
     * @param bundled   the direct dependencies to bundle, whose part of the tree takes a backpatch
     * @param fixed     what takes a backpatch, as {@code name installed → backpatch}, for the build's log
     */
    record Plan(SortedMap<String, String> direct, SortedMap<String, String> overrides, SortedSet<String> bundled,
                SortedSet<String> fixed) {

        boolean isEmpty() {
            return bundled.isEmpty();
        }
    }

    /** The index's backpatches: for each package, each fixed upstream version and its newest backpatch. */
    static Map<String, SortedMap<String, String>> index(JsonNode index) {
        Map<String, SortedMap<String, String>> fixes = new TreeMap<>();
        for (Map.Entry<String, JsonNode> pkg : index.path("newest").properties()) {
            SortedMap<String, String> byUpstream = new TreeMap<>(NpmBackpatches::compare);
            for (Map.Entry<String, JsonNode> fix : pkg.getValue().properties()) {
                byUpstream.put(fix.getKey(), fix.getValue().asText());
            }
            fixes.put(pkg.getKey(), byUpstream);
        }
        return fixes;
    }

    /**
     * The backpatch to install in place of {@code installed}: the newest one on its line that fixes an upstream version
     * at or after it. Null when there is none, or when {@code installed} is a backpatch already.
     */
    static @Nullable String backpatchFor(@Nullable SortedMap<String, String> fixes, String installed) {
        if (fixes == null || installed.contains(MARKER) || parse(installed) == null) {
            return null;
        }
        String best = null;
        for (Map.Entry<String, String> fix : fixes.entrySet()) {
            if (sameLine(fix.getKey(), installed) && compare(fix.getKey(), installed) >= 0 &&
                (best == null || compare(fix.getValue(), best) > 0)) {
                best = fix.getValue();
            }
        }
        return best;
    }

    /**
     * What the package's production tree, as its lockfile has it, takes from the alliance.
     *
     * @param lockfile {@code package-lock.json}, lockfile version 2 or 3
     * @param own      whether a package is one of our own, which is never bundled and whose part of the tree is its own
     *                 to bundle
     */
    static Plan plan(JsonNode lockfile, Map<String, SortedMap<String, String>> index, Predicate<String> own) {
        JsonNode packages = lockfile.path("packages");
        // A package's peers come from whoever installs it, so they are never its to bundle.
        Set<String> directNames = dependencyNames(packages.path(""), false);

        SortedMap<String, String> direct = new TreeMap<>();
        SortedMap<String, String> overrides = new TreeMap<>();
        SortedSet<String> bundled = new TreeSet<>();
        SortedSet<String> fixed = new TreeSet<>();
        for (String name : directNames) {
            String at = own.test(name) ? null : resolve(packages, "", name);
            if (at == null) {
                continue;
            }
            for (String reached : reachable(packages, at, own)) {
                String reachedName = nameAt(reached);
                String installed = packages.path(reached).path("version").asText("");
                String backpatch = backpatchFor(index.get(reachedName), installed);
                if (backpatch == null) {
                    continue;
                }
                bundled.add(name);
                fixed.add(reachedName + " " + installed + " → " + backpatch);
                if (reached.equals(NODE_MODULES + reachedName) && directNames.contains(reachedName)) {
                    direct.put(reachedName, backpatch);
                }
                // A backpatch is a prerelease, which no range a dependent asks for takes on its own, so every install of
                // what it fixes is overridden, a direct dependency's too.
                overrides.put(reachedName + "@" + installed, backpatch);
            }
        }
        return new Plan(direct, overrides, bundled, fixed);
    }

    /**
     * Every install of the production tree a package reaches from where it is installed, itself included, short of our
     * own packages, development dependencies, links, and what another package bundles, which cannot be swapped from
     * outside.
     */
    private static Set<String> reachable(JsonNode packages, String start, Predicate<String> own) {
        Set<String> seen = new HashSet<>();
        Deque<String> next = new ArrayDeque<>();
        next.add(start);
        while (!next.isEmpty()) {
            String at = next.pop();
            JsonNode entry = packages.path(at);
            if (entry.path("dev").asBoolean(false) || entry.path("inBundle").asBoolean(false) ||
                entry.path("link").asBoolean(false) || !seen.add(at)) {
                continue;
            }
            for (String name : dependencyNames(entry, true)) {
                String dependency = own.test(name) ? null : resolve(packages, at, name);
                if (dependency != null) {
                    next.add(dependency);
                }
            }
        }
        return seen;
    }

    /** Where Node finds {@code name} from a package installed at {@code from}: its own node_modules, then upward. */
    static @Nullable String resolve(JsonNode packages, String from, String name) {
        for (String base = from; ; base = parent(base)) {
            String candidate = (base.isEmpty() ? "" : base + "/") + NODE_MODULES + name;
            if (packages.has(candidate)) {
                return candidate;
            }
            if (base.isEmpty()) {
                return null;
            }
        }
    }

    private static String parent(String at) {
        int nested = at.lastIndexOf("/" + NODE_MODULES);
        return nested < 0 ? "" : at.substring(0, nested);
    }

    private static String nameAt(String at) {
        return at.substring(at.lastIndexOf(NODE_MODULES) + NODE_MODULES.length());
    }

    /** The names a lockfile entry depends on at runtime: its dependencies, optional dependencies, and maybe peers. */
    private static Set<String> dependencyNames(JsonNode entry, boolean peers) {
        Set<String> names = new TreeSet<>();
        for (String field : peers ? List.of("dependencies", "optionalDependencies", "peerDependencies") :
                List.of("dependencies", "optionalDependencies")) {
            entry.path(field).properties().forEach(dependency -> names.add(dependency.getKey()));
        }
        return names;
    }

    /** Whether two versions are on the same line: the same major, and the same minor for a 0.x. */
    private static boolean sameLine(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        return x != null && y != null && x[0] == y[0] && (x[0] != 0 || x[1] == y[1]);
    }

    /** Semantic version order, a release after its prereleases; versions that are no semantic version last. */
    static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) {
            return x == null ? (y == null ? a.compareTo(b) : 1) : -1;
        }
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        String preA = prerelease(a);
        String preB = prerelease(b);
        if (preA.isEmpty() || preB.isEmpty()) {
            return preA.isEmpty() ? (preB.isEmpty() ? 0 : 1) : -1;
        }
        return Comparator.<String>naturalOrder().compare(preA, preB);
    }

    private static int @Nullable [] parse(String version) {
        String core = version.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String prerelease(String version) {
        String withoutBuild = version.split("\\+", 2)[0];
        int dash = withoutBuild.indexOf('-');
        return dash < 0 ? "" : withoutBuild.substring(dash + 1);
    }

    /** The alliance's tarball of a backpatch, as its npm registry serves it. */
    static String tarball(String npmUrl, String name, String version) {
        String base = name.substring(name.indexOf('/') + 1);
        return npmUrl + name + "/-/" + base + "-" + version + ".tgz";
    }

    /**
     * Bundles the backpatches into every Tar task named {@code npmPack}, the task the recipe repositories pack their npm
     * packages with: what npm packs as {@code package/node_modules}, and the package's {@code package.json} as rewritten
     * to bundle them.
     */
    static void apply(Project project, String npmUrl, String indexUrl, String username, String password) {
        TaskProvider<NpmBackpatchesTask> backpatches = project.getTasks().register("npmBackpatches",
                NpmBackpatchesTask.class, task -> {
                    task.setDescription("Bundles the Moderne Backpatch Alliance's npm backpatches that the package's " +
                                        "production dependencies take");
                    task.getPackageJson().fileProvider(project.provider(() -> packageJson(project)));
                    task.getLockfile().fileProvider(project.provider(() -> {
                        File lockfile = new File(npmProjectDir(project), "package-lock.json");
                        return lockfile.isFile() ? lockfile : null;
                    }));
                    task.getIndexUrl().set(indexUrl);
                    task.getNpmUrl().set(npmUrl);
                    task.getUsername().set(username);
                    task.getPassword().set(password);
                    task.getOwnScopes().set(OWN_SCOPES);
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("npm-backpatches"));
                    // npmVersion stamps the package.json packed; npmInstall's outputs include the lockfile.
                    task.dependsOn(project.getTasks().matching(t ->
                            "npmVersion".equals(t.getName()) || "npmInstall".equals(t.getName())));
                });
        project.getTasks().withType(Tar.class).configureEach(tar -> {
            if (!"npmPack".equals(tar.getName())) {
                return;
            }
            tar.dependsOn(backpatches);
            tar.from(backpatches.flatMap(NpmBackpatchesTask::getOutputDirectory),
                    spec -> spec.include("package/node_modules/**"));
            // eachFile, since filesMatching matches a file's path in its source rather than in the package.
            tar.eachFile(details -> {
                if (!"package/package.json".equals(details.getPath())) {
                    return;
                }
                File rewritten = backpatches.get().getOutputDirectory().file("package/package.json").get().getAsFile();
                if (rewritten.isFile()) {
                    try {
                        details.filter(Map.of("content", Files.readString(rewritten.toPath())),
                                NpmBackpatchesTask.Replace.class);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
        });
    }

    /** The package.json the package is packed with: the one npmVersion stamps, when there is one. */
    private static File packageJson(Project project) {
        Task npmVersion = project.getTasks().findByName("npmVersion");
        if (npmVersion != null) {
            for (File dir : npmVersion.getOutputs().getFiles()) {
                return new File(dir, "package.json");
            }
        }
        return new File(npmProjectDir(project), "package.json");
    }

    /** Where the node plugin says the npm project is, or else the project's own directory. */
    private static File npmProjectDir(Project project) {
        Object node = project.getExtensions().findByName("node");
        if (node != null) {
            try {
                Object dir = node.getClass().getMethod("getNodeProjectDir").invoke(node);
                if (dir instanceof DirectoryProperty property && property.isPresent()) {
                    return property.get().getAsFile();
                }
            } catch (ReflectiveOperationException ignored) {
                // An extension named node that is not the node plugin's
            }
        }
        return project.getProjectDir();
    }
}
