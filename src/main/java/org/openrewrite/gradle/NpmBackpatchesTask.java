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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Bundles the Moderne Backpatch Alliance's npm backpatches that a package's production dependencies take (see
 * {@link NpmBackpatches}): installs those dependencies in a staging copy of the package with each backpatch in place
 * of what it fixes, has npm pack them as the bundle, and writes the package's {@code package.json} to bundle them,
 * naming each direct one at its backpatch, which the bundled copy satisfies. Nothing when none applies.
 */
@DisableCachingByDefault(because = "Which backpatches there are is the alliance's index to say, at each build")
public abstract class NpmBackpatchesTask extends DefaultTask {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The package.json the package is packed with. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPackageJson();

    /** The package's lockfile, which says what its production dependencies are; without one nothing is bundled. */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLockfile();

    @Input
    public abstract Property<String> getIndexUrl();

    /** The alliance's npm registry, ending in a slash. */
    @Input
    public abstract Property<String> getNpmUrl();

    @Internal
    public abstract Property<String> getUsername();

    @Internal
    public abstract Property<String> getPassword();

    /** Our own packages' scopes, such as {@code @openrewrite/}, which are never bundled. */
    @Input
    public abstract ListProperty<String> getOwnScopes();

    /** Where the bundle goes, as {@code package/node_modules}, with {@code package/package.json}. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract ArchiveOperations getArchiveOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    public NpmBackpatchesTask() {
        // The index changes with every backpatch the alliance releases.
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void bundle() throws IOException, InterruptedException {
        File out = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(out));
        Files.createDirectories(out.toPath());
        ObjectNode packageJson = (ObjectNode) JSON.readTree(getPackageJson().get().getAsFile());
        String name = packageJson.path("name").asText();
        if (!getLockfile().isPresent()) {
            getLogger().lifecycle("{} has no package-lock.json, so none of the Backpatch Alliance's backpatches is bundled", name);
            return;
        }
        List<String> scopes = getOwnScopes().get();
        NpmBackpatches.Plan plan = NpmBackpatches.plan(JSON.readTree(getLockfile().get().getAsFile()),
                NpmBackpatches.index(index()), dependency -> scopes.stream().anyMatch(dependency::startsWith));
        if (plan.isEmpty()) {
            getLogger().info("None of {}'s production dependencies takes a backpatch of the Backpatch Alliance's", name);
            return;
        }

        File staging = getTemporaryDir();
        getFileSystemOperations().delete(spec -> spec.delete((Object[]) staging.listFiles()));
        String npmUrl = getNpmUrl().get();
        ObjectNode staged = packageJson.deepCopy();
        staged.remove("devDependencies");
        staged.remove("scripts");
        plan.direct().forEach((dependency, version) ->
                setSpec(staged, dependency, NpmBackpatches.tarball(npmUrl, dependency, version)));
        ObjectNode overrides = staged.has("overrides") ? (ObjectNode) staged.get("overrides") : staged.putObject("overrides");
        plan.overrides().forEach((selector, version) -> {
            String dependency = selector.substring(0, selector.lastIndexOf('@'));
            // npm takes no override of a direct dependency other than a reference to it.
            overrides.put(selector, version.equals(plan.direct().get(dependency)) ? "$" + dependency :
                    NpmBackpatches.tarball(npmUrl, dependency, version));
        });
        ArrayNode bundled = staged.putArray("bundleDependencies");
        plan.bundled().forEach(bundled::add);
        JSON.writerWithDefaultPrettyPrinter().writeValue(new File(staging, "package.json"), staged);
        Files.copy(getLockfile().get().getAsFile().toPath(), staging.toPath().resolve("package-lock.json"));

        npm(staging, "install", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error");
        File packed = new File(staging, "packed");
        Files.createDirectories(packed.toPath());
        npm(staging, "pack", "--ignore-scripts", "--loglevel=error", "--pack-destination", packed.getAbsolutePath());
        File[] tarballs = packed.listFiles((dir, file) -> file.endsWith(".tgz"));
        if (tarballs == null || tarballs.length != 1) {
            throw new GradleException("npm pack made no tarball of " + name + "'s bundle in " + packed);
        }
        getFileSystemOperations().copy(spec -> {
            spec.from(getArchiveOperations().tarTree(tarballs[0]));
            spec.include("package/node_modules/**");
            spec.into(out);
        });
        verify(out.toPath().resolve("package/node_modules"), plan);

        ObjectNode published = packageJson.deepCopy();
        plan.direct().forEach((dependency, version) -> setSpec(published, dependency, version));
        SortedSet<String> bundle = new TreeSet<>(plan.bundled());
        published.path("bundleDependencies").forEach(existing -> bundle.add(existing.asText()));
        ArrayNode publishedBundle = published.putArray("bundleDependencies");
        bundle.forEach(publishedBundle::add);
        Path rewritten = out.toPath().resolve("package/package.json");
        Files.createDirectories(rewritten.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(rewritten.toFile(), published);

        getLogger().lifecycle("Bundled the Backpatch Alliance's backpatches into {}, in {}: {}", name,
                String.join(", ", plan.bundled()), String.join(", ", plan.fixed()));
    }

    /** The alliance's npm index, which anyone may read. */
    private JsonNode index() throws IOException, InterruptedException {
        String url = getIndexUrl().get();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new GradleException("The Backpatch Alliance's npm index answered " + response.statusCode() + " at " + url);
        }
        return JSON.readTree(response.body());
    }

    /** npm in {@code dir}, with the alliance's credentials for its registry and nothing else of the build's. */
    private void npm(File dir, String... args) {
        URI registry = URI.create(getNpmUrl().get());
        String auth = Base64.getEncoder().encodeToString((getUsername().get() + ":" + getPassword().get())
                .getBytes(StandardCharsets.UTF_8));
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("os.name", "").toLowerCase().contains("win") ? "npm.cmd" : "npm");
        command.addAll(List.of(args));
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(dir);
            spec.commandLine(command);
            spec.environment("npm_config_//" + registry.getAuthority() + registry.getPath() + ":_auth", auth);
        });
    }

    /** That every backpatch is in the bundle: each direct one where the package requires it from, each other somewhere. */
    private static void verify(Path nodeModules, NpmBackpatches.Plan plan) throws IOException {
        List<String> installed = new ArrayList<>();
        if (Files.isDirectory(nodeModules)) {
            try (Stream<Path> files = Files.walk(nodeModules)) {
                for (Path pkg : files.filter(f -> f.getFileName().toString().equals("package.json")).toList()) {
                    JsonNode manifest = JSON.readTree(pkg.toFile());
                    installed.add(nodeModules.relativize(pkg.getParent()).toString().replace(File.separatorChar, '/') +
                                  "@" + manifest.path("version").asText());
                }
            }
        }
        plan.direct().forEach((dependency, version) -> {
            if (!installed.contains(dependency + "@" + version)) {
                throw new GradleException("npm bundled no " + dependency + " " + version + " where the package requires it");
            }
        });
        plan.overrides().forEach((selector, version) -> {
            String wanted = selector.substring(0, selector.lastIndexOf('@')) + "@" + version;
            if (installed.stream().noneMatch(at -> at.equals(wanted) || at.endsWith("/node_modules/" + wanted))) {
                throw new GradleException("npm bundled no " + wanted.replace("@" + version, " " + version));
            }
        });
    }

    /** Sets the spec of a dependency where the package declares it, among its dependencies or optional ones. */
    private static void setSpec(ObjectNode packageJson, String dependency, String spec) {
        for (String field : List.of("dependencies", "optionalDependencies")) {
            JsonNode declared = packageJson.get(field);
            if (declared instanceof ObjectNode dependencies && dependencies.has(dependency)) {
                dependencies.put(dependency, spec);
                return;
            }
        }
    }

    /** Replaces what a copy filters with {@code content}, as {@code npmPack} does the package's package.json. */
    public static class Replace extends FilterReader {

        public Replace(Reader in) {
            super(in);
        }

        public void setContent(String content) {
            in = new StringReader(content);
        }
    }
}
