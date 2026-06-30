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

import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import com.github.jk1.license.LicenseReportExtension;
import com.github.jk1.license.LicenseReportPlugin;
import com.github.jk1.license.render.ReportRenderer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.jvm.tasks.Jar;

import java.io.File;
import java.util.Map;
import java.util.Optional;

public class RewriteLicensePlugin implements Plugin<Project> {

    private static final String LICENSE_NAME = "Apache License Version 2.0";
    private static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(SpotlessPlugin.class);
        project.getPlugins().apply(LicenseReportPlugin.class);

        project.getExtensions().configure(LicenseReportExtension.class, ext -> {
            ext.renderers = new ReportRenderer[]{new com.github.jk1.license.render.CsvReportRenderer()};
        });

        project.getTasks().withType(Jar.class).configureEach(jar ->
                jar.getManifest().attributes(Map.of(
                        "License-Name", LICENSE_NAME,
                        "License-Url", LICENSE_URL
                )));

        File licenseHeader = project.getRootProject().file("gradle/licenseHeader.txt");

        project.getExtensions().configure(SpotlessExtension.class, spotless -> {
            spotless.setEnforceCheck(Optional
                    .ofNullable((String) project.findProperty("licenseStrictCheck"))
                    .map(Boolean::parseBoolean)
                    .orElse(true));
            spotless.java(java -> java.licenseHeaderFile(licenseHeader));
            spotless.kotlin(kotlin -> kotlin.licenseHeaderFile(licenseHeader));
            spotless.format("typescript", format -> {
                format.target("**/*.ts");
                format.licenseHeaderFile(licenseHeader, "(import|export|const|declare|let|var|function|class|interface|type|enum|namespace) ");
            });
        });
    }
}
