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

import nebula.plugin.publishing.maven.MavenBasePublishPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom;
import org.gradle.jvm.tasks.Jar;

import java.util.Map;

public class ModerneSourceAvailableLicensePlugin implements Plugin<Project> {

    private static final String LICENSE_NAME = "Moderne Source Available License";
    private static final String LICENSE_URL = "https://docs.moderne.io/licensing/moderne-source-available-license";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(MavenBasePublishPlugin.class);
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.publications(publications -> publications.withType(MavenPublication.class, this::configureLicense));

        project.getTasks().withType(Jar.class).configureEach(jar ->
                jar.getManifest().attributes(Map.of(
                        "License-Name", LICENSE_NAME,
                        "License-Url", LICENSE_URL
                )));
    }

    private void configureLicense(MavenPublication publication) {
        publication.pom(pom ->
            pom.licenses(licenses -> {
                ((DefaultMavenPom) licenses).getLicenses().clear();
                licenses.license(license -> {
                    license.getName().set(LICENSE_NAME);
                    license.getUrl().set(LICENSE_URL);
                });
            }));
    }
}
