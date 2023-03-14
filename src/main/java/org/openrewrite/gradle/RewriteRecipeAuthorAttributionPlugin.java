/*
 * Copyright 2022 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetOutput;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toSet;

public class RewriteRecipeAuthorAttributionPlugin implements Plugin<Project> {

    private static final Pattern RESOURCE_DESCRIPTOR_PATTERN = Pattern.compile("META-INF/rewrite/.*\\.yml");

    @Override
    public void apply(Project project) {
        try {
            execute(project);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void execute(Project project) throws IOException, InterruptedException {
        Path moduleDir = project.getProjectDir().toPath();
        Path repoDir = moduleDir;
        Set<SourceSet> sourceSets = mainSourceSets(project);
        Set<File> javaSrcDirs = sourceSets.stream().flatMap(ss -> ss.getJava().getSrcDirs().stream()).collect(Collectors.toSet());
        Set<File> resourcesSrcDirs = sourceSets.stream().flatMap(ss -> ss.getResources().getSrcDirs().stream()).collect(toSet());

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ObjectWriter objectWriter = mapper.writerWithDefaultPrettyPrinter();

        ClassGraph classGraph = buildProjectSourcesClassGraph(project);
        try (ScanResult scanResult = classGraph.scan()) {
            List<RecipeAttribution> recipeAttributions = new ArrayList<>();

            // declarative recipes
            for (Resource resource : scanResult.getResourcesMatchingPattern(RESOURCE_DESCRIPTOR_PATTERN)) {
                File classpathElementFile = resource.getClasspathElementFile();
                Path sourceFileName = Paths.get(resource.getPath());
                Optional<File> sourceFile = resourcesSrcDirs.stream().map(d -> d.toPath().resolve(sourceFileName).toFile()).filter(File::isFile).findFirst();
                if (classpathElementFile.isDirectory() && classpathElementFile.toPath().startsWith(moduleDir) && sourceFile.map(File::isFile).orElse(false)) {
                    Iterable<Node> nodes = new Yaml().composeAll(new StringReader(resource.getContentAsString()));
                    List<RecipeLineInformation> recipeLineInformation = new ArrayList<>();
                    for (Node node : nodes) {
                        collectRecipeLineInformation(node, recipeLineInformation);
                    }
                    if (!recipeLineInformation.isEmpty()) {
                        // FIXME process with `git blame` and add to result
                        Map<Integer, AuthorAttribution> blame = getBlame(repoDir, repoDir.relativize(sourceFile.get().toPath()));
                        for (RecipeLineInformation info : recipeLineInformation) {
                            Set<AuthorAttribution> authorAttributions = new HashSet<>();
                            for (int i = info.startLine; i <= info.endLine; i++) {
                                authorAttributions.add(blame.get(i));
                            }
                            RecipeAttribution attribution = new RecipeAttribution(info.name, new ArrayList<>(authorAttributions));
                            recipeAttributions.add(attribution);
                        }
                    }
                }
            }

            // imperative recipes
            for (ClassInfo recipeClass : scanResult.getSubclasses("org.openrewrite.Recipe")) {
                File classpathElementFile = recipeClass.getClasspathElementFile();
                if (classpathElementFile.isDirectory() && classpathElementFile.toPath().startsWith(moduleDir)) {
                    String sourceFileName = recipeClass.getPackageName().replace('.', '/') + "/" + recipeClass.getSourceFile();
                    Optional<File> sourceFile = javaSrcDirs.stream().map(d -> d.toPath().resolve(sourceFileName).toFile()).filter(File::isFile).findFirst();
                    if (sourceFile.map(File::isFile).orElse(false)) {
                        List<AuthorAttribution> authorAttribution = getAuthorAttribution(repoDir.relativize(sourceFile.get().toPath()));
                        RecipeAttribution attribution = new RecipeAttribution(recipeClass.getName(), authorAttribution);
                        recipeAttributions.add(attribution);
                    }
                }
            }

            System.out.println(objectWriter.writeValueAsString(recipeAttributions));
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class RecipeAttribution {
        final String recipe;
        final List<AuthorAttribution> attribution;

        public RecipeAttribution(String recipe, List<AuthorAttribution> attribution) {
            this.recipe = recipe;
            this.attribution = attribution;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class AuthorAttribution {
        final String name;
        final String email;
        @Nullable
        final Integer commits;

        public AuthorAttribution(String name, String email, @Nullable Integer commits) {
            this.name = name;
            this.email = email;
            this.commits = commits;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AuthorAttribution that = (AuthorAttribution) o;
            return Objects.equals(name, that.name) && Objects.equals(email, that.email) && Objects.equals(commits, that.commits);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, email, commits);
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class RecipeLineInformation {
        final String name;
        final int startLine;
        final int endLine;

        public RecipeLineInformation(String name, int startLine, int endLine) {
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    private static void collectRecipeLineInformation(Node node, List<RecipeLineInformation> result) {
        if (node instanceof MappingNode) {
            MappingNode mappingNode = (MappingNode) node;
            if (isRecipeNode(mappingNode)) {
                Optional<String> recipeName = mappingNode.getValue().stream()
                        .filter(t -> t.getKeyNode() instanceof ScalarNode && ((ScalarNode) t.getKeyNode()).getValue().equals("name"))
                        .map(NodeTuple::getValueNode)
                        .map(ScalarNode.class::cast)
                        .map(ScalarNode::getValue)
                        .findAny();
                recipeName.ifPresent(n -> {
                    result.add(new RecipeLineInformation(n, mappingNode.getStartMark().getLine(), mappingNode.getEndMark().getLine()));
                });
            } else {
                for (NodeTuple nodeTuple : mappingNode.getValue()) {
                    collectRecipeLineInformation(nodeTuple.getValueNode(), result);
                }
            }
        } else if (node instanceof SequenceNode) {
            SequenceNode sequenceNode = (SequenceNode) node;
            for (Node childNode : sequenceNode.getValue()) {
                collectRecipeLineInformation(childNode, result);
            }
        }
    }

    private static boolean isRecipeNode(MappingNode node) {
        for (NodeTuple nodeTuple : node.getValue()) {
            if (nodeTuple.getKeyNode() instanceof ScalarNode
                    && ((ScalarNode) nodeTuple.getKeyNode()).getValue().equals("type")
                    && nodeTuple.getValueNode() instanceof ScalarNode
                    && ((ScalarNode) nodeTuple.getValueNode()).getValue().equals("specs.openrewrite.org/v1beta/recipe")) {
                return true;
            }
        }
        return false;
    }

    static Pattern AUTHOR_PATTERN = Pattern.compile("([^<>]+?) <([^>]+)>");
    static Pattern BLAME_HEADER_PATTERN = Pattern.compile("[0-9a-f]{40} \\d+ (\\d+)");

    private static Map<Integer, AuthorAttribution> getBlame(Path repoDir, Path sourceFile) throws IOException, InterruptedException {
        return Optional.ofNullable(runGitCommand(Arrays.asList("blame", "--porcelain", "--", sourceFile.toString()), lines -> {
            Map<Integer, AuthorAttribution> result = new HashMap<>();
            int lineNumber = -1;
            String author = null;
            String authorEmail = null;
            for (String line : lines) {
                Matcher matcher = BLAME_HEADER_PATTERN.matcher(line);
                if (matcher.find()) {
                    if (lineNumber != -1) {
                        result.put(lineNumber, new AuthorAttribution(author, authorEmail, null));
                    }
                    lineNumber = Integer.parseInt(matcher.group(1));
                } else if (line.startsWith("author ")) {
                    author = line.substring(7);
                } else if (line.startsWith("author-mail ")) {
                    authorEmail = line.substring(12);
                }
            }
            if (lineNumber != -1) {
                result.put(lineNumber, new AuthorAttribution(author, authorEmail, null));
            }
            return result;
        })).orElse(Collections.emptyMap());
    }

    private static List<AuthorAttribution> getAuthorAttribution(Path sourceFile) throws IOException, InterruptedException {
        return Optional.ofNullable(runGitCommand(Arrays.asList("shortlog", "--numbered", "--summary", "--email", "HEAD", "--", sourceFile.toString()), lines -> {
            List<AuthorAttribution> result = new ArrayList<>();
            for (String line : lines) {
                String[] split = line.split("\\t");
                Matcher matcher = AUTHOR_PATTERN.matcher(split[1]);
                if (matcher.find()) {
                    AuthorAttribution attribution = new AuthorAttribution(matcher.group(1), matcher.group(2), Integer.parseInt(split[0].trim()));
                    result.add(attribution);
                }
            }
            return result;
        })).orElse(Collections.emptyList());
    }

    @Nullable
    private static <T> T runGitCommand(List<String> subCommand, Function<List<String>, T> resultConverter) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(subCommand);

        ProcessBuilder builder = new ProcessBuilder(command);
        File out = Files.createTempFile(null, null).toFile();
        File err = Files.createTempFile(null, null).toFile();
        out.deleteOnExit();
        err.deleteOnExit();
        builder.redirectOutput(out);
        builder.redirectError(err);
        Process process = builder.start();

        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            System.err.println("Failed to execute command `" + String.join(" ", command) + "`");
            process.destroy();
        } else if (process.exitValue() != 0) {
            System.err.println("Failed to execute command `" + String.join(" ", command) + "` (error code " + process.exitValue() + "):");
            Files.readAllLines(err.toPath()).forEach(System.err::println);
        } else {
            return resultConverter.apply(Files.readAllLines(out.toPath()));
        }
        return null;
    }

    private static ClassGraph buildProjectSourcesClassGraph(Project project) {
        Object[] classpath = projectClasses(project).toArray(new URI[0]);
        return new ClassGraph().overrideClasspath(classpath).enableClassInfo();
    }

    private static Set<SourceSet> mainSourceSets(Project project) {
        JavaPluginExtension extension = project.getExtensions().getByType(JavaPluginExtension.class);
        return extension.getSourceSets().stream()
                .filter(sourceSet -> sourceSet.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME))
                .collect(toSet());
    }

    private static Set<URI> projectClasses(Project project) {
        Set<SourceSet> sourceSets = mainSourceSets(project);
        Set<URI> classpath = sourceSets.stream()
                .map(SourceSet::getOutput)
                .flatMap(output -> StreamSupport.stream(output.getClassesDirs().spliterator(), false))
                .map(File::toURI)
                .collect(toSet());
        sourceSets.stream()
                .map(SourceSet::getOutput)
                .map(SourceSetOutput::getResourcesDir)
                .filter(Objects::nonNull)
                .map(File::toURI)
                .forEach(classpath::add);
        return classpath;
    }
}
