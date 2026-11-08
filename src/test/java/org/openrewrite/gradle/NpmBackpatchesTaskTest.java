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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NpmBackpatchesTaskTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USERNAME = "npm-user";
    private static final String PASSWORD = "npm-token";
    private static final String BACKPATCH = "1.0.1-backpatch-00001";

    @Test
    void thePackedPackageBundlesTheBackpatchesItsProductionDependenciesTake(@TempDir File dir) throws Exception {
        assumeTrue(npmAvailable(), "npm is not on the PATH");
        try (FakeNpm server = new FakeNpm(new File(dir, "server"))) {
            Map<String, String> npmEnv = npmEnvironment(server, new File(dir, "npm"));
            publishPackages(server, new File(dir, "packages"), npmEnv);
            File project = new File(dir, "project");
            writeProject(project, npmEnv);

            BuildResult result = gradle(project, server, npmEnv, true);

            assertThat(result.getOutput()).contains(
              "Bundled the Backpatch Alliance's backpatches into npm-consumer, in lib, wrapper: lib 1.0.0 → " + BACKPATCH);
            File unpacked = untar(new File(project, "build/distributions/pkg.tgz"), new File(dir, "unpacked"));
            assertThat(versionOf(new File(unpacked, "package/node_modules/lib"))).isEqualTo(BACKPATCH);
            assertThat(versionOf(new File(unpacked, "package/node_modules/wrapper"))).isEqualTo("1.0.0");
            assertThat(new File(unpacked, "package/node_modules/other")).doesNotExist();
            JsonNode packageJson = JSON.readTree(new File(unpacked, "package/package.json"));
            assertThat(packageJson.path("bundleDependencies").toString()).isEqualTo("[\"lib\",\"wrapper\"]");
            assertThat(packageJson.path("dependencies").path("lib").asText()).isEqualTo(BACKPATCH);
            assertThat(packageJson.path("dependencies").path("wrapper").asText()).isEqualTo("^1.0.0");
            assertThat(packageJson.path("dependencies").path("other").asText()).isEqualTo("^1.0.0");

            // Whoever installs the package gets the backpatch by both of the paths the package requires lib by, and
            // the rest from the registry.
            File consumer = new File(dir, "consumer");
            Files.createDirectories(consumer.toPath());
            Files.writeString(new File(consumer, "package.json").toPath(), "{\"name\": \"consumer\", \"private\": true}");
            run(consumer, npmEnv, npm(), "install", "--no-audit", "--no-fund",
              new File(project, "build/distributions/pkg.tgz").getAbsolutePath());
            assertThat(run(consumer, npmEnv, "node", "-e",
              "console.log(JSON.stringify(require('npm-consumer')))").trim())
              .isEqualTo("{\"lib\":\"backpatched\",\"wrapper\":\"backpatched\",\"other\":\"other\"}");
        }
    }

    @Test
    void withoutCredentialsThePackageIsPackedAsItWas(@TempDir File dir) throws Exception {
        assumeTrue(npmAvailable(), "npm is not on the PATH");
        try (FakeNpm server = new FakeNpm(new File(dir, "server"))) {
            Map<String, String> npmEnv = npmEnvironment(server, new File(dir, "npm"));
            publishPackages(server, new File(dir, "packages"), npmEnv);
            File project = new File(dir, "project");
            writeProject(project, npmEnv);

            gradle(project, server, npmEnv, false);

            File unpacked = untar(new File(project, "build/distributions/pkg.tgz"), new File(dir, "unpacked"));
            assertThat(new File(unpacked, "package/node_modules")).doesNotExist();
            JsonNode packageJson = JSON.readTree(new File(unpacked, "package/package.json"));
            assertThat(packageJson.has("bundleDependencies")).isFalse();
            assertThat(packageJson.path("dependencies").path("lib").asText()).isEqualTo("^1.0.0");
        }
    }

    /**
     * lib 1.0.0, which the alliance has backpatched; wrapper, which requires it; other, which nothing touches; all
     * three on the registry, and the backpatch and the index at the alliance.
     */
    private static void publishPackages(FakeNpm server, File work, Map<String, String> npmEnv) throws Exception {
        publish(server, work, npmEnv, "lib", "1.0.0", "{}", "module.exports = 'upstream';");
        publish(server, work, npmEnv, "wrapper", "1.0.0", "{\"lib\": \"^1.0.0\"}", "module.exports = require('lib');");
        publish(server, work, npmEnv, "other", "1.0.0", "{}", "module.exports = 'other';");
        File backpatch = pack(work, npmEnv, "lib", BACKPATCH, "{}", "module.exports = 'backpatched';");
        server.put("npm/lib/-/" + backpatch.getName(), Files.readAllBytes(backpatch.toPath()));
        server.put("feeds/index/npm.json", ("{\"newest\": {\"lib\": {\"1.0.0\": \"" + BACKPATCH + "\"}}}")
          .getBytes(StandardCharsets.UTF_8));
    }

    private static void publish(FakeNpm server, File work, Map<String, String> npmEnv, String name, String version,
                                String dependencies, String main) throws Exception {
        File tarball = pack(work, npmEnv, name, version, dependencies, main);
        byte[] bytes = Files.readAllBytes(tarball.toPath());
        server.put("registry/" + name + "/-/" + tarball.getName(), bytes);
        ObjectNode packument = JSON.createObjectNode();
        packument.put("name", name);
        packument.putObject("dist-tags").put("latest", version);
        ObjectNode manifest = packument.putObject("versions").putObject(version);
        manifest.put("name", name);
        manifest.put("version", version);
        manifest.set("dependencies", JSON.readTree(dependencies));
        ObjectNode dist = manifest.putObject("dist");
        dist.put("tarball", server.url() + "registry/" + name + "/-/" + tarball.getName());
        dist.put("shasum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)));
        dist.put("integrity", "sha512-" + Base64.getEncoder()
          .encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes)));
        server.put("registry/" + name, JSON.writeValueAsBytes(packument));
    }

    private static File pack(File work, Map<String, String> npmEnv, String name, String version, String dependencies,
                             String main) throws Exception {
        File source = new File(work, name + "-" + version);
        Files.createDirectories(source.toPath());
        Files.writeString(new File(source, "package.json").toPath(), """
          {"name": "%s", "version": "%s", "main": "index.js", "dependencies": %s}
          """.formatted(name, version, dependencies));
        Files.writeString(new File(source, "index.js").toPath(), main);
        run(source, npmEnv, npm(), "pack", "--pack-destination", work.getAbsolutePath());
        return new File(work, name + "-" + version + ".tgz");
    }

    /** A package depending on all three, with its lockfile, packed as the recipe repositories pack theirs. */
    private static void writeProject(File project, Map<String, String> npmEnv) throws Exception {
        Files.createDirectories(project.toPath());
        Files.writeString(new File(project, "package.json").toPath(), """
          {
            "name": "npm-consumer",
            "version": "1.0.0",
            "main": "index.js",
            "dependencies": {"lib": "^1.0.0", "wrapper": "^1.0.0", "other": "^1.0.0"}
          }
          """);
        Files.writeString(new File(project, "index.js").toPath(),
          "module.exports = {lib: require('lib'), wrapper: require('wrapper'), other: require('other')};");
        run(project, npmEnv, npm(), "install", "--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund");
        Files.writeString(new File(project, "settings.gradle").toPath(), "rootProject.name = 'npm-consumer'");
        //language=groovy
        Files.writeString(new File(project, "build.gradle").toPath(), """
          plugins {
              id 'org.openrewrite.build.recipe-repositories'
          }
          tasks.register('npmPack', Tar) {
              from(projectDir) {
                  include 'package.json', 'index.js'
                  into 'package'
              }
              archiveFileName = 'pkg.tgz'
              compression = Compression.GZIP
              destinationDirectory = layout.buildDirectory.dir('distributions')
          }
          """);
    }

    private static BuildResult gradle(File project, FakeNpm server, Map<String, String> npmEnv, boolean credentials) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("MAVEN_CENTRAL_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_backpatch"));
        env.putAll(npmEnv);
        List<String> arguments = new ArrayList<>(List.of("npmPack", "-PcodegenomeUsername=", "-PcodegenomePassword=",
          "-PbackpatchUrl=" + server.url() + "maven/"));
        arguments.add("-PbackpatchUsername=" + (credentials ? USERNAME : ""));
        arguments.add("-PbackpatchPassword=" + (credentials ? PASSWORD : ""));
        return GradleRunner.create()
          .withProjectDir(project)
          .withPluginClasspath()
          .withEnvironment(env)
          .withArguments(arguments)
          .build();
    }

    /** npm against the fake registry, with a cache and user configuration of its own. */
    private static Map<String, String> npmEnvironment(FakeNpm server, File home) throws IOException {
        Files.createDirectories(home.toPath());
        File userConfig = new File(home, "npmrc");
        Files.writeString(userConfig.toPath(), "");
        return Map.of(
          "NPM_CONFIG_REGISTRY", server.url() + "registry/",
          "NPM_CONFIG_CACHE", new File(home, "cache").getAbsolutePath(),
          "NPM_CONFIG_USERCONFIG", userConfig.getAbsolutePath());
    }

    private static String versionOf(File pkg) throws IOException {
        return JSON.readTree(new File(pkg, "package.json")).path("version").asText();
    }

    private static File untar(File tarball, File into) throws Exception {
        Files.createDirectories(into.toPath());
        run(into, Map.of(), "tar", "-xzf", tarball.getAbsolutePath(), "-C", into.getAbsolutePath());
        return into;
    }

    private static boolean npmAvailable() {
        try {
            return new ProcessBuilder(npm(), "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String npm() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "npm.cmd" : "npm";
    }

    private static String run(File dir, Map<String, String> env, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(String.join(" ", command) + ":\n" + output).isZero();
        return output;
    }

    /**
     * An npm registry under {@code /registry/}, and the alliance: its npm index, which anyone reads, and its npm
     * registry under {@code /npm/}, which only its user reads.
     */
    private static final class FakeNpm implements AutoCloseable {
        private static final String AUTHORIZATION = "Basic " + Base64.getEncoder()
          .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

        private final Map<String, byte[]> files = new HashMap<>();
        private final HttpServer server;

        FakeNpm(File root) throws IOException {
            Files.createDirectories(root.toPath());
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", this::serve);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        void put(String path, byte[] content) {
            files.put("/" + path, content);
        }

        private void serve(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/npm/") &&
                    !AUTHORIZATION.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"alliance\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                byte[] content = files.get(path);
                if (content == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                if (!path.endsWith(".tgz")) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                }
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
