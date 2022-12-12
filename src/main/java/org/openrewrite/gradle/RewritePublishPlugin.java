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

import nebula.plugin.publishing.maven.MavenPublishPlugin;
import nebula.plugin.publishing.maven.MavenResolvedDependenciesPlugin;
import nebula.plugin.publishing.maven.MavenShadowPublishPlugin;
import nebula.plugin.publishing.maven.license.MavenApacheLicensePlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class RewritePublishPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(JavaBasePlugin.class);
        project.getPlugins().apply(SigningPlugin.class);
        project.getPlugins().apply(MavenPublishPlugin.class);
        project.getPlugins().apply(MavenResolvedDependenciesPlugin.class);
        project.getPlugins().apply(MavenApacheLicensePlugin.class);

        project.getPlugins().withId("com.github.johnrengelman.shadow", plugin ->
                project.getPlugins().apply(MavenShadowPublishPlugin.class));

        project.getTasks().withType(GenerateModuleMetadata.class).configureEach(task ->
                task.setEnabled(false));

        project.getExtensions().configure(SigningExtension.class, ext -> {
            ext.setRequired(!project.getVersion().toString().endsWith("SNAPSHOT") || project.hasProperty("forceSigning"));
            ext.useInMemoryPgpKeys(
                    (String) project.findProperty("signingKey"),
                    (String) project.findProperty("signingPassword"));
            ext.sign(project.getExtensions()
                    .getByType(PublishingExtension.class)
                    .getPublications()
                    .findByName("nebula"));
        });

        project.getExtensions().configure(PublishingExtension.class, ext ->
                ext.getPublications().named("nebula", MavenPublication.class, pub -> {
                    pub.suppressPomMetadataWarningsFor("runtimeElements");

                    pub.pom(pom -> {
                        pom.withXml(xml -> {
                            Element dependencies = (Element) xml.asElement().getElementsByTagName("dependencies").item(0);
                            NodeList dependencyList = dependencies.getElementsByTagName("dependency");
                            int length = dependencyList.getLength();
                            for (int i = 0; i < length; i++) {
                                Node dependency = dependencyList.item(i);
                                if (dependency.getNodeType() == Node.ELEMENT_NODE) {
                                    Element dependencyElement = (Element) dependency;
                                    Node scope = ((Element) dependency).getElementsByTagName("scope").item(0);
                                    if (scope != null && (scope.getTextContent().equals("provided") ||
                                                          dependencyElement.getElementsByTagName("groupId").item(0)
                                                                  .getTextContent().equals("org.projectlombok"))) {
                                        dependencies.removeChild(dependency);
                                        i--;
                                        length--;
                                    }
                                }
                            }
                        });
                    });
                })
        );
    }
}
