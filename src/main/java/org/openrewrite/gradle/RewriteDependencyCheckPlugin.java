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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.owasp.dependencycheck.gradle.DependencyCheckPlugin;
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension;

public class RewriteDependencyCheckPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(DependencyCheckPlugin.class);

        float failBuildOnCVSS = Float
                .parseFloat(System.getenv("FAIL_BUILD_ON_CVSS") != null ? System.getenv("FAIL_BUILD_ON_CVSS") : "9");

        // check to see if `suppressions.xml` exists in project root
        if (project.file("suppressions.xml").exists()) {
            project.getExtensions().configure(DependencyCheckExtension.class, ext -> {
                ext.setSuppressionFile(project.file("suppressions.xml").getPath());
            });
        }

        project.getExtensions().configure(DependencyCheckExtension.class, ext -> {
            ext.getAnalyzers().setAssemblyEnabled(false);
            ext.getAnalyzers().setNodeAuditEnabled(false);
            ext.getAnalyzers().setNodeEnabled(false);
            ext.setFailBuildOnCVSS(failBuildOnCVSS);
            ext.getNvd().setApiKey(System.getenv("NVD_API_KEY"));

        });

    }
}
