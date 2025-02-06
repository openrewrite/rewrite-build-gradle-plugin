/*
 * Copyright 2025 the original author or authors.
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

import org.apache.ivy.plugins.latest.LatestLexicographicStrategy;
import org.apache.ivy.plugins.version.*;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.java.internal.parser.TypeTable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class RecipeDependenciesTypeTableTask extends DefaultTask {

    private static final ChainVersionMatcher versionMatcher = new ChainVersionMatcher();

    static {
        versionMatcher.add(new ExactVersionMatcher());
        versionMatcher.add(new LatestVersionMatcher());
        versionMatcher.add(new PatternVersionMatcher());
        versionMatcher.add(new SubVersionMatcher());
        versionMatcher.add(new VersionRangeMatcher("latest", new LatestLexicographicStrategy()));
    }

    @TaskAction
    void download() throws IOException {
        SourceDirectorySet resources = getProject().getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName("main")
                .getResources();

        for (File sourceDirectory : resources.getSourceDirectories()) {
            File tsvFile = new File(sourceDirectory, TypeTable.DEFAULT_RESOURCE_PATH);
            File parentFile = tsvFile.getParentFile();
            if (!parentFile.exists() && !parentFile.mkdirs()) {
                throw new IllegalStateException("Unable to create " + parentFile);
            }

            RecipeDependenciesExtension extension = getProject().getExtensions().getByType(RecipeDependenciesExtension.class);
            try (TypeTable.Writer writer = TypeTable.newWriter(Files.newOutputStream(tsvFile.toPath()))) {
                for (Map.Entry<Dependency, File> dependency : extension.getResolved().entrySet()) {
                    String group = requireNonNull(dependency.getKey().getGroup(), "group");
                    String artifact = dependency.getKey().getName();
                    String version = requireNonNull(dependency.getKey().getVersion(), "version");
                    writer.jar(group, artifact, version).write(dependency.getValue().toPath());
                    getLogger().info("Wrote %s:%s:%s to %s", group, artifact, version, tsvFile);
                }
            }
        }
    }
}
