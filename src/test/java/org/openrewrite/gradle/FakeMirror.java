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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * A Maven repository on the loopback address, to stand in for the Artifactory mirror in the builds a test
 * launches, so that what they resolve through it can be asserted without the network. Like Artifactory, it
 * answers a request that comes without credentials with a challenge, so a build only gets anything out of
 * it by presenting the ones it was given.
 * <p>
 * Plain HTTP because Gradle makes an exception for 127.0.0.1 to its insistence on HTTPS, and a file
 * repository would not do: Gradle refuses to give one credentials.
 */
final class FakeMirror implements AutoCloseable {

    private static final String USERNAME = "mirror-user";
    private static final String PASSWORD = "mirror-token";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder()
      .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    private final File root;
    private final HttpServer server;

    FakeMirror(File root) throws IOException {
        this.root = root;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    /** The variables CI exports, in place of whatever this build was itself given. */
    Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.keySet().removeIf(name -> name.startsWith("REWRITE_GRADLE_MIRROR_") ||
                                      name.startsWith("ORG_GRADLE_PROJECT_artifactory"));
        env.put("REWRITE_GRADLE_MIRROR_URL", url());
        env.put("REWRITE_GRADLE_MIRROR_USERNAME", USERNAME);
        env.put("REWRITE_GRADLE_MIRROR_PASSWORD", PASSWORD);
        return env;
    }

    void publish(String group, String artifact, String version) throws IOException {
        publishPom(group, artifact, version, "");
        write(new File(versionDir(group, artifact, version), artifact + "-" + version + ".jar"), "not really a jar");
    }

    void publishPom(String group, String artifact, String version, String body) throws IOException {
        //language=xml
        write(new File(versionDir(group, artifact, version), artifact + "-" + version + ".pom"), """
          <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              %s
          </project>
          """.formatted(group, artifact, version, body));

        File module = versionDir(group, artifact, version).getParentFile();
        StringBuilder versions = new StringBuilder();
        for (String published : requireNonNull(module.list((dir, name) -> new File(dir, name).isDirectory()))) {
            versions.append("<version>").append(published).append("</version>");
        }
        //language=xml
        write(new File(module, "maven-metadata.xml"), """
          <metadata>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <versioning>
                  <versions>%s</versions>
              </versioning>
          </metadata>
          """.formatted(group, artifact, versions));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!AUTHORIZATION.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"mirror\"");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            File file = new File(root, exchange.getRequestURI().getPath());
            if (!file.isFile()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] content = Files.readAllBytes(file.toPath());
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(content.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    private File versionDir(String group, String artifact, String version) {
        return new File(root, group.replace('.', '/') + "/" + artifact + "/" + version);
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }
}
