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

public class ModerneProprietaryLicensePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // Empty JARs are OK: https://central.sonatype.org/publish/requirements/#supply-javadoc-and-sources
        project.getTasks().named("sourcesJar", Jar.class).get().setEnabled(false);

        Jar sourceJar = project.getTasks().create("emptySourceJar", Jar.class, task -> {
            task.from("README.md");
            task.getArchiveClassifier().set("sources");
        });

        project.getTasks().named("assemble", task -> task.dependsOn(sourceJar));

        project.getPlugins().apply(MavenBasePublishPlugin.class);
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.publications(publications -> publications.withType(MavenPublication.class,
                p -> configureLicense(p, sourceJar)));
    }

    private void configureLicense(MavenPublication publication, Jar sourceJar) {
        publication.artifact(sourceJar);

        publication.pom(pom -> {
            pom.licenses(licenses -> {
                licenses.license(license -> {
                    ((DefaultMavenPom) licenses).getLicenses().clear();
                    license.getName().set("Moderne Proprietary License");
                    license.getUrl().set("https://docs.moderne.io/licensing/overview");
                });
            });
        });
    }
}
