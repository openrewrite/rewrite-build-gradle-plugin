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

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import lombok.Value;
import lombok.With;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.PersonIdent;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.*;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CacheableTask
public class RewriteRecipeAuthorAttributionTask extends DefaultTask {

    private final DirectoryProperty sources = getProject().getObjects().directoryProperty();

    public void setSources(File sourceDirectory) {
        sources.set(sourceDirectory);
    }

    @SkipWhenEmpty
    @InputDirectory
    @PathSensitive(PathSensitivity.NAME_ONLY)
    @IgnoreEmptyDirectories
    public DirectoryProperty getSources() {
        return sources;
    }

    private Set<URI> classpath;

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath.getFiles().stream().map(File::toURI).collect(Collectors.toSet());
    }

    @Internal
    public Set<URI> getClasspath() {
        return classpath;
    }

    @OutputDirectory
    public Path getOutputDirectory() {
        return getProject().getBuildDir().toPath().resolve("rewrite/attribution")
                .resolve(sources.get().getAsFile().getName());
    }

    @Value
    static class Contributor {
        String name;
        String email;

        @With
        int lineCount;

        public static List<Contributor> distinct(List<Contributor> contribs) {
            List<Contributor> deduped = new ArrayList<>(contribs.size());
            for (Contributor contrib : contribs) {
                if (deduped.stream().noneMatch(c -> c.getEmail().equals(contrib.getEmail()))) {
                    deduped.add(contrib);
                }
            }

            Map<String, Contributor> byName = new LinkedHashMap<>(deduped.size());
            for (Contributor contributor : deduped) {
                String name = contributor.getName();
                if (byName.containsKey(name)) {
                    if (byName.get(name).getEmail().endsWith("@users.noreply.github.com")) {
                        byName.put(name, contributor);
                    }
                } else {
                    byName.put(name, contributor);
                }
            }

            return new ArrayList<>(byName.values());
        }
    }

    @Value
    static class Attribution {
        String type;
        String recipeName;
        List<Contributor> contributors;
    }

    @TaskAction
    void execute(InputChanges inputChanges) {
        try (Git g = Git.open(getProject().getRootDir())) {
            Map<String, String> recipeFileNameToFqn = new HashMap<>();
            try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                    .overrideClasspath(classpath)
                    .scan()) {
                ClassInfoList recipeClasses = scanResult.getSubclasses("org.openrewrite.Recipe");
                for (ClassInfo recipeClass : recipeClasses) {
                    recipeFileNameToFqn.put(recipeClass.getSourceFile(), recipeClass.getName());
                }
            }
            YAMLMapper mapper = new YAMLMapper();
            Path outputDir = getOutputDirectory();
            for (FileChange change : inputChanges.getFileChanges(sources)) {
                File recipeFile = change.getFile();
                String recipeFqn = recipeFileNameToFqn.get(recipeFile.getName());
                if (recipeFqn == null) {
                    continue;
                }
                Path targetPath = outputDir.resolve(recipeFqn + ".yml");
                if (change.getChangeType() == ChangeType.REMOVED) {
                    Files.delete(targetPath);
                    continue;
                }

                // git commands only accept forward slashes in paths, regardless of operating system
                String relativePath = getProject().getRootDir().toPath()
                        .relativize(recipeFile.toPath()).toString()
                        .replace('\\', '/');
                List<Contributor> contributors = listContributors(g, relativePath);

                Attribution attribution = new Attribution("specs.openrewrite.org/v1beta/attribution", recipeFqn, contributors);
                String yaml = mapper.writeValueAsString(attribution);

                Files.createDirectories(targetPath.getParent());
                Files.write(targetPath, yaml.getBytes());
            }

        } catch (Exception e) {
            getLogger().warn("Unable to complete attribution of recipe authors", e);
        }
    }

    private static List<Contributor> listContributors(Git g, String relativeUnixStylePath) throws GitAPIException {
        Map<Contributor, Integer> contributors = new HashMap<>();

        BlameResult blame = g.blame().setFilePath(relativeUnixStylePath).call();
        RawText resultContents = blame.getResultContents();
        if (resultContents == null) {
            return Collections.emptyList();
        }

        for (int i = 0; i < resultContents.size(); i++) {
            PersonIdent author = blame.getSourceAuthor(i);
            contributors.compute(new Contributor(author.getName(), author.getEmailAddress(), 0), (k, v) -> v == null ? 1 : v + 1);
        }

        return Contributor.distinct(contributors.entrySet().stream()
                .map(entry -> entry.getKey().withLineCount(entry.getValue()))
                .sorted(Comparator.comparing(Contributor::getLineCount).reversed())
                .collect(Collectors.toList()));
    }
}
