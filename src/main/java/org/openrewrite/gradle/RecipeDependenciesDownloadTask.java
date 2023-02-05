/*
 * Copyright 2023 the original author or authors.
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

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.impldep.org.apache.ivy.core.module.id.ModuleRevisionId;
import org.gradle.internal.impldep.org.apache.ivy.plugins.latest.LatestLexicographicStrategy;
import org.gradle.internal.impldep.org.apache.ivy.plugins.version.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class RecipeDependenciesDownloadTask extends DefaultTask {

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
            File parserClasspath = new File(sourceDirectory, "META-INF/rewrite/classpath");
            if (!parserClasspath.exists() && !parserClasspath.mkdirs()) {
                throw new IllegalStateException("Unable to create directory " + parserClasspath);
            }

            RecipeDependenciesExtension extension = getProject().getExtensions().getByType(RecipeDependenciesExtension.class);
            for (Map.Entry<Dependency, File> dependency : extension.getResolved().entrySet()) {
                Path dependencyFile = dependency.getValue().toPath();
                Path destination = parserClasspath.toPath().resolve(dependencyFile.getFileName());

                if (Files.exists(destination)) {
                    continue;
                }

                for (File otherDependency : requireNonNull(parserClasspath.listFiles())) {
                    if (shouldReplace(dependency.getKey(), otherDependency.getName())) {
                        Files.delete(otherDependency.toPath());
                    }
                }

                Files.copy(dependencyFile, destination);
            }
        }
    }

    static boolean shouldReplace(Dependency dependency, String otherName) {
        return versionMatcher.accept(
                ModuleRevisionId.newInstance("group", "artifact", requireNonNull(dependency.getVersion())),
                ModuleRevisionId.newInstance("group", "name", otherName.substring(dependency.getName().length() + 1))
        );
    }
}
