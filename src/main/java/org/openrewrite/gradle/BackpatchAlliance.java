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

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.ProviderFactory;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The Moderne Backpatch Alliance: upstream releases with a security fix ported onto them, at versions such as
 * {@code 2.6.0.1-backpatch-00001}. With credentials for its repository, the alliance's platform raises each module on a
 * project's main classpaths to the newest backpatch later than what the build would otherwise select, and the POM names
 * the backpatches the build took.
 */
final class BackpatchAlliance {

    static final String NAME = "backpatch";
    static final String URL = "https://backpatch.moderne.io/maven/";
    static final String GROUP = "io.moderne.backpatch";
    static final String PLATFORM = GROUP + ":backpatch-platform:latest.release";

    /**
     * Holds the platform. Only the main compile and runtime classpaths extend it, so no published variant carries the
     * platform, and tests keep the versions they ask for.
     */
    static final String CONFIGURATION = "backpatchPlatform";

    private static final String MARKER = "-backpatch-";

    /** Every backpatch's version, and no upstream release's, as a repository content filter matches it. */
    static final String VERSIONS = ".*" + MARKER + ".*";

    private final String url;
    private final String username;
    private final String password;

    private BackpatchAlliance(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    /**
     * Credentials are the {@code backpatchUsername} and {@code backpatchPassword} Gradle properties, an account of the
     * alliance's that downloads; {@code backpatchUrl} points at another copy of its repository.
     *
     * @return {@code null} when either is unset, which leaves the build as it was.
     */
    static @Nullable BackpatchAlliance ifConfigured(ProviderFactory providers) {
        String username = providers.gradleProperty("backpatchUsername").getOrElse("");
        String password = providers.gradleProperty("backpatchPassword").getOrElse("");
        if (username.isEmpty() || password.isEmpty()) {
            return null;
        }
        return new BackpatchAlliance(providers.gradleProperty("backpatchUrl").getOrElse(URL), username, password);
    }

    /** The alliance's repository, asked for backpatches alone, and the only one asked for its platform. */
    void addRepository(RepositoryHandler repos) {
        MavenArtifactRepository alliance = repos.maven(repo -> {
            repo.setName(NAME);
            repo.setUrl(url);
            repo.credentials(creds -> {
                creds.setUsername(username);
                creds.setPassword(password);
            });
            repo.content(content -> content.includeVersionByRegex(".*", ".*", VERSIONS));
        });
        // The platform sets versions for the whole build, so no other repository may offer one.
        repos.exclusiveContent(exclusive -> exclusive
                .forRepositories(alliance)
                .filter(filter -> filter.includeGroup(GROUP)));
    }

    /** The platform on the main compile and runtime classpaths, once the project is a Java one. */
    static void applyPlatform(Project project) {
        project.getPlugins().withType(JavaPlugin.class, java -> {
            ConfigurationContainer configurations = project.getConfigurations();
            Configuration platform = configurations.dependencyScope(CONFIGURATION, scope ->
                    scope.setDescription("The Moderne Backpatch Alliance's platform, which the main classpaths extend")).get();
            project.getDependencies().add(CONFIGURATION, project.getDependencies().platform(PLATFORM));
            configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, classpath -> classpath.extendsFrom(platform));
            configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, classpath -> classpath.extendsFrom(platform));
        });
    }

    /**
     * Names each module the runtime classpath took at a backpatch as a dependency of the module's own, unless the POM
     * names it already. Gradle, Maven 3 and OpenRewrite ignore a dependency's dependencyManagement for what it depends
     * on, so a managed version would not reach consumers. The scope is compile where consumers compile against the
     * backpatched module through a compile dependency, runtime otherwise.
     */
    static void nameBackpatches(Element pom, Configuration compileClasspath, Configuration runtimeClasspath) {
        Element dependencies = find(pom, "dependencies");
        Set<String> named = new HashSet<>();
        Set<String> compileScoped = new HashSet<>();
        for (Element dependency : dependencies == null ? List.<Element>of() : children(dependencies, "dependency")) {
            String module = textOf(dependency, "groupId") + ":" + textOf(dependency, "artifactId");
            named.add(module);
            String scope = textOf(dependency, "scope");
            if (scope == null || "compile".equals(scope)) {
                compileScoped.add(module);
            }
        }

        SortedMap<String, String> backpatches = new TreeMap<>();
        for (ResolvedComponentResult component : runtimeClasspath.getIncoming().getResolutionResult().getAllComponents()) {
            if (component.getId() instanceof ModuleComponentIdentifier id && id.getVersion().contains(MARKER) &&
                !GROUP.equals(id.getGroup()) && !named.contains(id.getGroup() + ":" + id.getModule())) {
                backpatches.put(id.getGroup() + ":" + id.getModule(), id.getVersion());
            }
        }
        if (backpatches.isEmpty()) {
            return;
        }

        Set<String> compiled = compiledThrough(compileClasspath, compileScoped);
        Document owner = pom.getOwnerDocument();
        Element target = dependencies == null ? child(pom, "dependencies") : dependencies;
        backpatches.forEach((module, version) -> {
            String[] groupAndName = module.split(":");
            Element dependency = owner.createElement("dependency");
            dependency.appendChild(text(owner, "groupId", groupAndName[0]));
            dependency.appendChild(text(owner, "artifactId", groupAndName[1]));
            dependency.appendChild(text(owner, "version", version));
            dependency.appendChild(text(owner, "scope", compiled.contains(module) ? "compile" : "runtime"));
            target.appendChild(dependency);
        });
    }

    /** The modules a consumer compiles against through the module's dependencies in compile scope. */
    private static Set<String> compiledThrough(Configuration compileClasspath, Set<String> compileScoped) {
        Deque<ResolvedComponentResult> next = new ArrayDeque<>();
        for (ResolvedComponentResult first : selected(compileClasspath.getIncoming().getResolutionResult().getRoot())) {
            if (compileScoped.contains(module(first))) {
                next.add(first);
            }
        }
        Set<ComponentIdentifier> seen = new HashSet<>();
        Set<String> modules = new HashSet<>();
        while (!next.isEmpty()) {
            ResolvedComponentResult component = next.pop();
            if (seen.add(component.getId())) {
                modules.add(module(component));
                next.addAll(selected(component));
            }
        }
        return modules;
    }

    /** What the component's dependencies selected, leaving out constraints, which bring in no module themselves. */
    private static List<ResolvedComponentResult> selected(ResolvedComponentResult component) {
        List<ResolvedComponentResult> selected = new ArrayList<>();
        for (DependencyResult dependency : component.getDependencies()) {
            if (dependency instanceof ResolvedDependencyResult resolved && !resolved.isConstraint()) {
                selected.add(resolved.getSelected());
            }
        }
        return selected;
    }

    private static String module(ResolvedComponentResult component) {
        ModuleVersionIdentifier id = component.getModuleVersion();
        return id == null ? "" : id.getGroup() + ":" + id.getName();
    }

    private static @Nullable Element find(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static @Nullable String textOf(Element parent, String name) {
        Element child = find(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    /** The element's child of that name, added when it has none. */
    private static Element child(Element parent, String name) {
        Element found = find(parent, name);
        return found != null ? found : (Element) parent.appendChild(parent.getOwnerDocument().createElement(name));
    }

    private static Element text(Document owner, String name, String value) {
        Element element = owner.createElement(name);
        element.setTextContent(value);
        return element;
    }
}
