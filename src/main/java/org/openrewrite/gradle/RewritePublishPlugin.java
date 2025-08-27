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
import nebula.plugin.publishing.publications.JavadocJarPlugin;
import nebula.plugin.publishing.publications.SourceJarPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class RewritePublishPlugin implements Plugin<Project> {

    private static final Attribute<String> CONFIGURATION_ORIGIN_ATTRIBUTE = 
            Attribute.of("org.openrewrite.configuration.origin", String.class);

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(JavaBasePlugin.class);
        project.getPlugins().apply(SourceJarPlugin.class);
        project.getPlugins().apply(JavadocJarPlugin.class);
        project.getPlugins().apply(SigningPlugin.class);
        project.getPlugins().apply(MavenPublishPlugin.class);
        project.getPlugins().apply(MavenResolvedDependenciesPlugin.class);
        project.getPlugins().apply(MavenApacheLicensePlugin.class);

        // Fix Gradle 9.0+ configuration attribute conflicts between archives and signatures
        // If the signatures configuration is removed, we need to also remove this attribute addition
        project.getConfigurations().named("signatures", config -> {
            config.getAttributes().attribute(CONFIGURATION_ORIGIN_ATTRIBUTE, "signing-plugin");
        });

        // This plugin does not do anything if the shadow plugin is not applied, so it is safe to always apply it
        project.getPlugins().apply(MavenShadowPublishPlugin.class);

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

        ConfigurationContainer configurations = project.getConfigurations();
        Configuration provided = configurations.create("provided");
        Configuration compileOnly = configurations.findByName("compileOnly");
        if (compileOnly != null) {
            compileOnly.extendsFrom(provided);
        }
        Configuration testImplementation = configurations.findByName("testImplementation");
        if (testImplementation != null) {
            testImplementation.extendsFrom(provided);
        }

        project.getExtensions().configure(PublishingExtension.class, ext ->
                ext.getPublications().named("nebula", MavenPublication.class, pub -> {
                    pub.suppressPomMetadataWarningsFor("runtimeElements");

                    pub.pom(pom -> pom.withXml(xml -> {
                        for(int i = 0; i < xml.asElement().getChildNodes().getLength(); i++) {
                            Node node = xml.asElement().getChildNodes().item(i);
                            if (node.getNodeType() == Node.ELEMENT_NODE && "dependencies".equals(node.getNodeName())) {
                                Element dependencies = (Element) node;
                                Document owner = dependencies.getOwnerDocument();
                                for (ResolvedDependency providedDep : provided.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
                                    Element dependencyElement = dependencies.getOwnerDocument().createElement("dependency");
                                    Element groupId = owner.createElement("groupId");
                                    groupId.setTextContent(providedDep.getModuleGroup());
                                    dependencyElement.appendChild(groupId);
                                    Element artifactId = owner.createElement("artifactId");
                                    artifactId.setTextContent(providedDep.getModuleName());
                                    dependencyElement.appendChild(artifactId);
                                    Element version = owner.createElement("version");
                                    version.setTextContent(providedDep.getModuleVersion());
                                    dependencyElement.appendChild(version);
                                    Element scope = owner.createElement("scope");
                                    scope.setTextContent("provided");
                                    dependencyElement.appendChild(scope);
                                    dependencies.appendChild(dependencyElement);
                                }
                                break;
                            }
                        }
                    }));
                })
        );
    }
}
