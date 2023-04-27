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
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import org.openrewrite.*;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.JavaParser;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class RecipeExamplesTask extends DefaultTask {
    private final DirectoryProperty sources = getProject().getObjects().directoryProperty();

    @SkipWhenEmpty
    @InputDirectory
    @PathSensitive(PathSensitivity.NAME_ONLY)
    @IgnoreEmptyDirectories
    public DirectoryProperty getSources() {
        return sources;
    }

    public void setSources(File sourceDirectory) {
        sources.set(sourceDirectory);
    }

    @OutputDirectory
    public Path getOutputDirectory() {
        return getProject().getBuildDir().toPath().resolve("rewrite/examples")
            .resolve(sources.get().getAsFile().getName());
    }

    @TaskAction
    void execute() {
        List<File> allJavaFiles = new ArrayList<>();
        collectFiles(sources.get().getAsFile(), allJavaFiles);
        extractExamples(allJavaFiles, new InMemoryExecutionContext());
    }

    private void collectFiles(File directory, List<File> allJavaFiles) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    collectFiles(file, allJavaFiles);
                } else {
                    if (file.getName().endsWith(".java")) {
                        allJavaFiles.add(file);
                    }
                }
            }
        }
    }

    private void extractExamples(List<File> allJavaFiles, ExecutionContext ctx) {
        JavaParser.Builder<? extends JavaParser, ?> builder = JavaParser.fromJavaVersion();
        Parser<?> parser = builder
            .classpath(JavaParser.runtimeClasspath())
            .build();

        List<Parser.Input> inputs = new ArrayList<>();
        List<SourceFile> sourceFiles;

        for (File file : allJavaFiles) {
            Parser.Input input = new Parser.Input(file.toPath(),
                () -> {
                    try {
                        return new FileInputStream(file);
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                });
            inputs.add(input);
        }

        getLogger().lifecycle("Parsing " + allJavaFiles.size() + " java files...");
        sourceFiles = (List<SourceFile>) parser.parseInputs(inputs, null, ctx);
        getLogger().lifecycle("Parsing java files finished.");
        for (SourceFile s : sourceFiles) {
            ExamplesExtractor examplesExtractor = new ExamplesExtractor();
            examplesExtractor.visit(s, ctx);

            String yamlContent = examplesExtractor.printRecipeExampleYaml();
            if (StringUtils.isNotEmpty(yamlContent)) {
                writeYamlFile(s.getSourcePath().getFileName().toString(), getOutputDirectory(), yamlContent);
            }
        }
    }

    void writeYamlFile(String originalTestFileName, Path outputPath, String data) {
        int index = originalTestFileName.lastIndexOf('.');
        String nameWithoutExtension = (index == -1) ? originalTestFileName : originalTestFileName.substring(0, index);
        String fileName = nameWithoutExtension + ".yml";

        Path path = Paths.get(outputPath.toString(), fileName);
        try {
            if (!Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }

            if (Files.exists(path)) {
                return;
            }

            FileWriter writer = new FileWriter(path.toFile());
            writer.write(data);
            writer.close();
            getLogger().lifecycle("Generated recipe examples yaml '{}' for the test file '{}'",  fileName, originalTestFileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
