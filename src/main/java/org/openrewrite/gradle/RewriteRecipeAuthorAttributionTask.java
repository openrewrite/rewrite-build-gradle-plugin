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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import org.eclipse.jgit.lib.PersonIdent;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.*;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CacheableTask
public class RewriteRecipeAuthorAttributionTask extends DefaultTask {

    @Override
    public String getDescription() {
        return "Extracts git contribution history to credit recipe authors.";
    }

    @Override
    public String getGroup() {
        return "OpenRewrite";
    }

    private final DirectoryProperty sources = getProject().getObjects().directoryProperty()
            .convention(getProject().getLayout().getProjectDirectory().dir("src/main/java"));

    public void setSources(File sourceDirectory) {
        sources.set(sourceDirectory);
    }

    @IgnoreEmptyDirectories
    @InputDirectory
    @PathSensitive(PathSensitivity.NAME_ONLY)
    @SkipWhenEmpty
    public DirectoryProperty getSources() {
        return sources;
    }

    private Set<URI> classpath = Collections.emptySet();

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath.getFiles().stream().map(File::toURI).collect(Collectors.toSet());
    }

    @Internal
    public Set<URI> getClasspath() {
        return classpath;
    }

    private boolean isYamlRecipes = false;

    public void setIsYamlRecipes(boolean isYamlRecipes) {
        this.isYamlRecipes = isYamlRecipes;
    }

    @Internal
    public boolean isYamlRecipes() {
        return isYamlRecipes;
    }

    @OutputDirectory
    public Path getOutputDirectory() {
        return getProject().getLayout().getBuildDirectory().get().getAsFile().toPath().resolve("rewrite/attribution")
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
            if (isYamlRecipes) {
                // For YAML recipes, map file names to recipe names extracted from YAML
                processYamlRecipes(g, inputChanges);
                return;
            }
            processJavaRecipes(inputChanges, g);
        } catch (Exception e) {
            getLogger().warn("Unable to complete attribution of recipe authors", e);
        }
    }

    private void processJavaRecipes(InputChanges inputChanges, Git g) throws IOException, GitAPIException {
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
            BlameResult blame = g.blame().setFilePath(relativePath).call();
            if (blame == null || blame.getResultContents() == null) {
                continue;
            }

            List<Contributor> contributors = extractContributorsForRange(blame, 0, blame.getResultContents().size());
            Attribution attribution = new Attribution("specs.openrewrite.org/v1beta/attribution", recipeFqn, contributors);
            String yaml = mapper.writeValueAsString(attribution);

            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, yaml);
        }
    }

    private void processYamlRecipes(Git g, InputChanges inputChanges) throws Exception {
        YAMLMapper mapper = new YAMLMapper();
        Path outputDir = getOutputDirectory();

        for (FileChange change : inputChanges.getFileChanges(sources)) {
            File yamlFile = change.getFile();
            if (!yamlFile.getName().endsWith(".yml") && !yamlFile.getName().endsWith(".yaml")) {
                continue;
            }

            if (change.getChangeType() == ChangeType.REMOVED) {
                Path targetPath = outputDir.resolve(yamlFile.getName().replaceFirst("\\.[^.]+$", "") + ".yml");
                Files.delete(targetPath);
                continue;
            }

            try {
                // git commands only accept forward slashes in paths, regardless of operating system
                String relativePath = getProject().getRootDir().toPath()
                        .relativize(yamlFile.toPath()).toString()
                        .replace('\\', '/');
                BlameResult blame = g.blame().setFilePath(relativePath).call();

                for (RecipeLineNumbers recipe : recipeLineNumbers(yamlFile)) {
                    List<Contributor> contributors = extractContributorsForRange(blame, recipe.startLine, recipe.endLine);

                    Attribution attr = new Attribution("specs.openrewrite.org/v1beta/attribution", recipe.name, contributors);
                    String yaml = mapper.writeValueAsString(attr);

                    Path targetPath = outputDir.resolve(recipe.name + ".yml");
                    Files.createDirectories(targetPath.getParent());
                    Files.writeString(targetPath, yaml);
                }
            } catch (Exception e) {
                getLogger().warn("Unable to process YAML recipe file: " + yamlFile, e);
            }
        }
    }

    record RecipeLineNumbers(String name, int startLine, int endLine) {
    }

    private static List<RecipeLineNumbers> recipeLineNumbers(File file) throws IOException {
        MappingIterator<Map<String, Object>> mapMappingIterator = new YAMLMapper()
                .readValues(new YAMLFactory().createParser(file), new TypeReference<>() {
                });

        String recipeName = null;
        int startLine = 0;
        int endLine;
        List<RecipeLineNumbers> list = new ArrayList<>();
        while (mapMappingIterator.hasNext()) {
            // Store the previous entry
            endLine = mapMappingIterator.getCurrentLocation().getLineNr() - 1;
            if (recipeName != null) {
                list.add(new RecipeLineNumbers(recipeName, startLine, endLine - 1));
            }

            // Start reading the next entry
            startLine = endLine;
            Map<String, Object> entry = mapMappingIterator.next();
            recipeName = "specs.openrewrite.org/v1beta/recipe".equals(entry.get("type")) ? (String) entry.get("name") : null;
        }
        // Store the last entry
        endLine = mapMappingIterator.getCurrentLocation().getLineNr() - 1;
        if (recipeName != null) {
            list.add(new RecipeLineNumbers(recipeName, startLine, endLine));
        }
        return list;
    }

    private static List<Contributor> extractContributorsForRange(@Nullable BlameResult blame, int start, int end) {
        if (blame == null) {
            return Collections.emptyList();
        }
        Map<Contributor, Integer> contributors = new HashMap<>();
        for (int i = start; i < end; i++) {
            PersonIdent author = blame.getSourceAuthor(i);
            contributors.compute(
                    new Contributor(author.getName(), author.getEmailAddress(), 0),
                    (k, v) -> v == null ? 1 : v + 1);
        }
        return Contributor.distinct(contributors.entrySet().stream()
                .map(entry -> entry.getKey().withLineCount(entry.getValue()))
                .sorted(Comparator.comparing(Contributor::getLineCount).reversed())
                .toList());
    }
}
