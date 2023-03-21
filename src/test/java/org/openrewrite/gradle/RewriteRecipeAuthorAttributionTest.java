package org.openrewrite.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class RewriteRecipeAuthorAttributionTest {

    static BuildResult runGradle(Path buildRoot, String... args) {
        return GradleRunner.create()
                .withProjectDir(buildRoot.toFile())
                .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0)
                .withArguments(args)
                .forwardOutput()
                .withPluginClasspath()
                .build();
    }

    @Test
    void javaAttribution(@TempDir Path repositoryRoot) throws IOException {
        writeSampleProject(repositoryRoot);
        BuildResult result = runGradle(repositoryRoot, "assemble", "-Dorg.gradle.caching=true");
        assertThat(requireNonNull(result.task(":rewriteRecipeAuthorAttributionJava")).getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        File expectedOutput = new File(repositoryRoot.toFile(), "build/resources/main/META-INF/rewrite/attribution/org.openrewrite.org.openrewrite.ExampleRecipe.yml");
        assertThat(expectedOutput.exists()).isTrue();
        String contents = Files.readString(expectedOutput.toPath());
        assertThat(contents).contains("email: \"sam@moderne.io\"");
        assertThat(contents).contains("recipeName: \"org.openrewrite.org.openrewrite.ExampleRecipe\"");

        BuildResult rerunResult = runGradle(repositoryRoot, "clean", "assemble", "-Dorg.gradle.caching=true");
        assertThat(requireNonNull(rerunResult.task(":rewriteRecipeAuthorAttributionJava")).getOutcome())
                .as("Task should have been cached on the first execution and retrieved from the cache after cleaning")
                .isEqualTo(TaskOutcome.FROM_CACHE);
    }

    static void writeSampleProject(Path repositoryRoot) {
        try(ZipInputStream zip = new ZipInputStream(requireNonNull(RewriteRecipeAuthorAttributionTest.class
                .getClassLoader().getResourceAsStream("sample.zip")))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if(entry.isDirectory()) {
                    continue;
                }
                Path path = repositoryRoot.resolve(entry.getName());
                //noinspection ResultOfMethodCallIgnored
                path.getParent().toFile().mkdirs();

                try (OutputStream outputStream = Files.newOutputStream(path)) {
                    zip.transferTo(outputStream);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
