package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class RecipeExamplesTask extends DefaultTask {

    private File sourceDir;
    private File destinationDir;
    private int stopCount = 0;

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
        System.out.println("Processing unit tests from " + sourceDir + " to " + destinationDir);

        printFilesRecursive(sourceDir);

        System.out.println("ExampleTaskIsRunning");
    }


    private void printFilesRecursive(File directory) {
        if (stopCount > 1) {
            return;
        }

        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    printFilesRecursive(file);
                } else {
                    System.out.println(file.getAbsolutePath());
                    System.out.println(file.getName());

                    if (file.getName().endsWith(".java")) {
                        // todo, extract recipe examples
                        //

                        stopCount++;
                        System.out.println("PRINT JAVA FILE " + file.getName());

                        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                System.out.println(line);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Error reading file: " + file.getAbsolutePath(), e);
                        }
                    }
                }
            }
        }
    }
}
