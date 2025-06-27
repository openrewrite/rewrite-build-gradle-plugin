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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.java.internal.parser.TypeTable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public abstract class RecipeDependenciesTypeTableTask extends DefaultTask {

    /**
     * The target directory where the type table file will be written.
     * <p>
     * Defaults to {@code src/main/resources} in the project directory.
     */
    @OutputDirectory
    public abstract DirectoryProperty getTargetDir();

    public RecipeDependenciesTypeTableTask() {
        getTargetDir().convention(
                getProject().getLayout().getProjectDirectory().dir("src/main/resources")
        );
    }

    @Override
    public String getDescription() {
        return "Processes dependencies from the \"recipeDependencies\" DSL into Type Tables for use with OpenRewrite's JavaParser.";
    }

    @Override
    public String getGroup() {
        return "OpenRewrite";
    }

    @TaskAction
    void download() throws IOException {
        File matchedDir = findMatchingDir();
        File tsvFile = createTsvFile(matchedDir);

        RecipeDependenciesExtension extension = getProject().getExtensions().getByType(RecipeDependenciesExtension.class);
        try (TypeTable.Writer writer = TypeTable.newWriter(Files.newOutputStream(tsvFile.toPath()))) {
            for (Map.Entry<Dependency, File> dependency : extension.getResolved().entrySet()) {
                String group = requireNonNull(dependency.getKey().getGroup(), "group");
                String artifact = dependency.getKey().getName();
                // Determine actual version; e.g. 5.+ might resolve to 5.3.39
                String version = dependency.getValue().getName()
                        .substring(artifact.length() + 1)
                        .replaceAll(".jar$", "");
                writer.jar(group, artifact, version).write(dependency.getValue().toPath());
                getLogger().info("Wrote %s:%s:%s to %s".formatted(group, artifact, version, tsvFile));
            }
        }
    }

    /**
     * Finds the directory that matches the target directory specified in the task.
     * <p>
     * It checks against the main source set resources directories.
     *
     * @return The matching directory.
     * @throws GradleException if the target directory is not found in the main source set resources directories.
     */
    private File findMatchingDir() {
        File targetDirFile = getTargetDir().get().getAsFile();

        SourceDirectorySet resources = getProject().getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName("main")
                .getResources();

        Set<File> resourcesDirs = resources.getSourceDirectories().getFiles();

        return resourcesDirs.stream()
                .filter(dir -> dir.getAbsolutePath().equals(targetDirFile.getAbsolutePath()))
                .findFirst()
                .orElseThrow(() -> new GradleException("Provided target directory '" + targetDirFile.getAbsolutePath() +
                        "' is not found in main source set resources directories. " +
                        "Available directories: " + resourcesDirs));
    }

    private File createTsvFile(File matchedDir) {
        File tsvFile = new File(matchedDir, TypeTable.DEFAULT_RESOURCE_PATH.replace(".tsv.gz", ".tsv.zip"));
        File parentFile = tsvFile.getParentFile();
        if (!parentFile.exists() && !parentFile.mkdirs()) {
            throw new IllegalStateException("Unable to create " + parentFile);
        } else if (tsvFile.exists()) {
            tsvFile.delete();
        }
        return new File(matchedDir, TypeTable.DEFAULT_RESOURCE_PATH);
    }
}
