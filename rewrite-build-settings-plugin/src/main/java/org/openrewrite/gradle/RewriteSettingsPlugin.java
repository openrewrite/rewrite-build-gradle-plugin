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

import org.gradle.api.Plugin;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

/**
 * Adds the Code Genome Project to {@code pluginManagement.repositories}, so that the
 * {@code org.openrewrite} artifacts the build plugins themselves are built on resolve.
 * <p>
 * {@code org.openrewrite.build.recipe-repositories} does this for a project's own dependencies, but a
 * plugin's classpath is resolved from the repositories settings declares, before any project exists.
 * Those default to the Gradle Plugin Portal, which proxies Maven Central, and since recipe and
 * language libraries stopped publishing there, {@code org.openrewrite:rewrite-core} and its siblings
 * are no longer on it — so applying any of these plugins fails before it can configure anything.
 * <p>
 * This plugin carries no {@code org.openrewrite} dependencies of its own, which is what lets it
 * resolve from the plugin portal alone and then make everything else resolvable.
 */
public class RewriteSettingsPlugin implements Plugin<Settings> {

    static final String ID = "org.openrewrite.build.settings";
    static final String CGP_ID = "codegenome";
    static final String CGP_URL = "https://artifacts.codegenomeproject.org/maven";

    private static final Logger logger = Logging.getLogger(RewriteSettingsPlugin.class);

    @Override
    public void apply(Settings settings) {
        String username = gradleProperty(settings, "codegenomeUsername");
        String password = gradleProperty(settings, "codegenomePassword");
        if (username.isEmpty() || password.isEmpty()) {
            // Fork pull requests cannot see repository secrets. Nothing here can make their builds
            // resolve, but failing outright would only replace one unhelpful error with another.
            logger.warn("Code Genome Project credentials are absent, so " + CGP_URL + " is not among the\n" +
                        "repositories plugins resolve from. An org.openrewrite build plugin will fail to resolve its\n" +
                        "own dependencies. Set codegenomeUsername and codegenomePassword in ~/.gradle/gradle.properties,\n" +
                        "or expose them as ORG_GRADLE_PROJECT_codegenomeUsername and ORG_GRADLE_PROJECT_codegenomePassword.");
            return;
        }

        RepositoryHandler repositories = settings.getPluginManagement().getRepositories();
        // An empty handler is what makes Gradle fall back to the plugin portal, so adding to one opts out of it
        if (repositories.isEmpty()) {
            repositories.gradlePluginPortal();
        }
        if (repositories.findByName(CGP_ID) != null) {
            return;
        }
        // Appended rather than prepended, which a RepositoryHandler cannot do. Nothing is excluded from
        // the repositories already there: a plugin's classpath is pinned to exact versions, so a hit
        // anywhere is the same artifact, and excluding org.openrewrite from the portal would take the
        // plugin markers with it.
        repositories.maven(repo -> {
            repo.setName(CGP_ID);
            repo.setUrl(CGP_URL);
            repo.credentials(credentials -> {
                credentials.setUsername(username);
                credentials.setPassword(password);
            });
            repo.content(content -> {
                content.includeGroupAndSubgroups("org.openrewrite");
                content.includeGroupAndSubgroups("io.moderne");
            });
        });
    }

    private static String gradleProperty(Settings settings, String name) {
        return settings.getProviders().gradleProperty(name).getOrElse("");
    }
}
