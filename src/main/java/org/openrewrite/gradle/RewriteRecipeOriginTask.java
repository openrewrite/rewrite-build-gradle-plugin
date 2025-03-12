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

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.*;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CacheableTask
public class RewriteRecipeOriginTask extends DefaultTask {

    private final DirectoryProperty sources = getProject().getObjects().directoryProperty();

    private Set<URI> classpath;
    private String recipeLicenseName;
    private String recipeLicenseUrl;
    private String repoBaseUrl;

    public void setSources(File sourceDirectory) {
        sources.set(sourceDirectory);
    }

    @TaskAction
    void execute(InputChanges inputChanges) throws IOException {
        Map<String, String> fileNameToFqn = collectRecipes();

        for (FileChange change : inputChanges.getFileChanges(sources)) {
            File file = change.getFile();
            //determine FQN
            String recipeFqn = fileNameToFqn.get(file.getName());
            if (recipeFqn == null) {
                continue;
            }
            // prepare output file
            Path targetPath = getOutputDirectory().resolve(recipeFqn + ".yml");
            if (change.getChangeType() == ChangeType.REMOVED) {
                Files.delete(targetPath);
                continue;
            }
            Files.createDirectories(targetPath.getParent());
            // persist content
            String yaml = String.format(
                    "type: specs.openrewrite.org/v1beta/origin%n" +
                            "recipeName: %s%n" +
                            "recipeUrl: \"%s/tree/main/%s\"%n" +
                            "recipeLicenseUrl: \"%s\"%n" +
                            "recipeLicenseName: \"%s\"%n",
                    recipeFqn, repoBaseUrl, file.getAbsoluteFile(), recipeLicenseUrl, recipeLicenseName);
            Files.write(targetPath, yaml.getBytes());
        }
    }

    private @NotNull Map<String, String> collectRecipes() {
        Map<String, String> fileNameToFqn = new HashMap<>();
        try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                .overrideClasspath(classpath)
                .scan()) {
            ClassInfoList recipeClasses = scanResult.getSubclasses("org.openrewrite.Recipe");
            for (ClassInfo recipeClass : recipeClasses) {
                String file = recipeClass.getSourceFile();
                String fqn = recipeClass.getName();
                fileNameToFqn.put(file, fqn);
            }
        }
        return fileNameToFqn;
    }

    @OutputDirectory
    public Path getOutputDirectory() {
        return getProject().getBuildDir().toPath().resolve("rewrite/origin")
                .resolve(sources.get().getAsFile().getName());
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath.getFiles().stream().map(File::toURI).collect(Collectors.toSet());
    }

    @IgnoreEmptyDirectories
    @InputDirectory
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public DirectoryProperty getSources() {
        return this.sources;
    }

    @Internal
    public Set<URI> getClasspath() {
        return this.classpath;
    }

    @Input
    public String getRecipeLicenseName() {
        return this.recipeLicenseName;
    }

    @Input
    public String getRecipeLicenseUrl() {
        return this.recipeLicenseUrl;
    }

    @Input
    public String getRepoBaseUrl() {
        return this.repoBaseUrl;
    }

    public void setRecipeLicenseName(String recipeLicenseName) {
        this.recipeLicenseName = recipeLicenseName;
    }

    public void setRecipeLicenseUrl(String recipeLicenseUrl) {
        this.recipeLicenseUrl = recipeLicenseUrl;
    }

    public void setRepoBaseUrl(String repoBaseUrl) {
        this.repoBaseUrl = repoBaseUrl;
    }
}
