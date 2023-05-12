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

import com.github.jk1.license.LicenseReportExtension;
import com.github.jk1.license.LicenseReportPlugin;
import com.github.jk1.license.render.ReportRenderer;
import com.hierynomus.gradle.license.LicenseBasePlugin;
import com.hierynomus.gradle.license.tasks.LicenseFormat;
import nl.javadude.gradle.plugins.license.LicenseExtension;
import nl.javadude.gradle.plugins.license.LicensePlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;

public class RewriteLicensePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(LicensePlugin.class);
        project.getPlugins().apply(LicenseReportPlugin.class);

        project.getExtensions().configure(LicenseReportExtension.class, ext -> {
            ext.renderers = new ReportRenderer[] { new com.github.jk1.license.render.CsvReportRenderer() };
        });

        project.getTasks().withType(LicenseFormat.class, task -> {
            ((org.gradle.api.plugins.ExtraPropertiesExtension) task.getExtensions().getByName("ext"))
                    .set("year", Calendar.getInstance().get(Calendar.YEAR));
        });

        project.getExtensions().configure(LicenseExtension.class, ext -> {
            ext.setSkipExistingHeaders(true);
            ext.getExcludePatterns().addAll(Arrays.asList("**/*.tokens", "**/*.config", "**/*.interp", "**/*.txt", "**/*.bat",
                    "**/*.zip", "**/*.csv", "**/gradlew", "**/*.dontunpack", "**/*.css",
                    "**/*.editorconfig", "**/*.md", "**/*.jar"));
            ext.setHeader(project.getRootProject().file("gradle/licenseHeader.txt"));
            ext.mapping(new HashMap<String, String>() {{
                put("kt", "SLASHSTAR_STYLE");
                put("java", "SLASHSTAR_STYLE");
                put("ts", "SLASHSTAR_STYLE");
            }});
            ext.setStrictCheck(true);
        });
    }
}
