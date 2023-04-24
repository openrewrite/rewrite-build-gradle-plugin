package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.*;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.ExamplesExtractor;
import org.openrewrite.java.JavaParser;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class RecipeExamplesTask extends DefaultTask {

    private File sourceDir;
    private File destinationDir;

    @InputDirectory
    public File getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(File sourceDir) {
        this.sourceDir = sourceDir;
    }

    @OutputDirectory
    public File getDestinationDir() {
        return destinationDir;
    }

    public void setDestinationDir(File destinationDir) {
        this.destinationDir = destinationDir;
    }

    @TaskAction
    void processTests() {
        extractFilesRecursive(sourceDir);
    }

    private void extractFilesRecursive(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    extractFilesRecursive(file);
                } else {
                    if (file.getName().endsWith(".java")) {
                        extractExamples(file, new InMemoryExecutionContext());
                    }
                }
            }
        }
    }

    private void extractExamples(File file, ExecutionContext ctx) {
        JavaParser.Builder<? extends JavaParser, ?> builder = JavaParser.fromJavaVersion();
        Parser<?> parser = builder
            .classpath(JavaParser.runtimeClasspath())
            .classpath("rewrite", "rewrite-java", "rewrite-core", "rewrite-test")
            .build();

        List<Parser.Input> inputs = new ArrayList<>();
        List<SourceFile> sourceFiles;

        Parser.Input input = new Parser.Input(file.toPath(),
            () -> {
                try {
                    return new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        inputs.add(input);
        sourceFiles = (List<SourceFile>) parser.parseInputs(inputs, null, ctx);

        for (SourceFile s : sourceFiles) {
            ExamplesExtractor examplesExtractor = new ExamplesExtractor();
            examplesExtractor.visit(s, ctx);

            String yamlContent = examplesExtractor.printRecipeExampleYaml();
            if (StringUtils.isNotEmpty(yamlContent)) {
                getLogger().lifecycle("Generated recipe examples for file {}", file.getName());

                // todo, to be removed
                getLogger().lifecycle(examplesExtractor.printRecipeExampleYaml());
            }
        }
    }
}
