package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.*;
import org.openrewrite.java.ExamplesExtractor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.TreeVisitingPrinter;

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

                        // print file, to be removed
                        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                System.out.println(line);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Error reading file: " + file.getAbsolutePath(), e);
                        }

                        extractExamples(file, new InMemoryExecutionContext());
                    }
                }
            }
        }
    }

    private void extractExamples(File file, ExecutionContext ctx) {
        Parser.Builder javaParserBuilder = JavaParser.fromJavaVersion();
        Parser<?> parser = javaParserBuilder.build();
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
            System.out.println(TreeVisitingPrinter.printTree(s));

            ExamplesExtractor examplesExtractor = new ExamplesExtractor();
            examplesExtractor.visit(s, ctx);

            System.out.println(examplesExtractor.printRecipeExampleYaml());
        }


    }
}
