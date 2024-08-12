/*
 * Copyright 2024 the original author or authors.
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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.owasp.dependencycheck.gradle.DependencyCheckPlugin;
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class RewriteDependencyCheckPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(DependencyCheckPlugin.class);

        float failBuildOnCVSS = Float
                .parseFloat(System.getenv("FAIL_BUILD_ON_CVSS") != null ? System.getenv("FAIL_BUILD_ON_CVSS") : "9");
        String format = System.getenv("DEPENDENCY_CHECK_FORMAT") != null ? System.getenv("OWASP_REPORT_FORMAT") : "HTML";

        // upsert suppressions file
        generateSuppressionsFile(project);

        project.getExtensions().configure(DependencyCheckExtension.class, ext -> {
            ext.getAnalyzers().setAssemblyEnabled(false);
            ext.getAnalyzers().setNodeAuditEnabled(false);
            ext.getAnalyzers().setNodeEnabled(false);
            ext.setFailBuildOnCVSS(failBuildOnCVSS);
            ext.setFormat(format);
            ext.getNvd().setApiKey(System.getenv("NVD_API_KEY"));

        });

    }

    private void generateSuppressionsFile(Project project) {
        // Ensure the build directory exists
        File buildDir = project.getLayout().getBuildDirectory().get().getAsFile();
        if (!buildDir.exists()) {
            project.getLogger().info("Creating build directory: {}", buildDir.getAbsolutePath());
            buildDir.mkdirs();
        }
        // Copy suppressions.xml from the current loaded class's resources directory to the project's build directory
        File sharedSuppressionsFile = new File(buildDir, "suppressions.xml");
        File projectSuppressionsFile = new File(project.getProjectDir(), "suppressions.xml");

        // Upsert shared suppression file
        try (InputStream inputStream = getClass().getResourceAsStream("/suppressions.xml")) {
            if (inputStream != null) {
                Files.copy(inputStream, sharedSuppressionsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new FileNotFoundException("Resource suppressions.xml not found");
            }
        } catch (IOException e) {
            project.getLogger().error("Failed to copy suppressions.xml", e);
            throw new UncheckedIOException("Failed to copy suppressions.xml", e);
        }

        project.getExtensions().configure(DependencyCheckExtension.class, ext -> {
            List<String> suppressionFiles = new ArrayList<>();
            suppressionFiles.add(sharedSuppressionsFile.getAbsolutePath());
            project.getLogger().info("Adding shared suppressions file: {}", sharedSuppressionsFile.getAbsolutePath());
            if (projectSuppressionsFile.exists()) {
                project.getLogger().info("Adding project suppressions file: {}", projectSuppressionsFile.getAbsolutePath());
                suppressionFiles.add(projectSuppressionsFile.getAbsolutePath());
            }
            ext.setSuppressionFiles(suppressionFiles);
        });
    }
}