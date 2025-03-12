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
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPomLicense;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import static org.eclipse.jgit.util.StringUtils.capitalize;

public class RewriteRecipeOriginPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        String recipeLicense = determineLicense(project);
        String repoUrl = determineRepoUrl(project);

        JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
        if (java == null) {
            return;
        }
        SourceSet mainSource = java.getSourceSets().getByName("main");
        TaskContainer tasks = project.getTasks();
        TaskProvider<Copy> copyOrigins = tasks.register("copyOrigins", Copy.class);
        //noinspection UnstableApiUsage
        tasks.named("processResources", ProcessResources.class).configure(task -> {
            task.dependsOn(copyOrigins);
        });

        for (File sourceDir : mainSource.getAllSource().getSrcDirs()) {
            TaskProvider<RewriteRecipeOriginTask> attr = tasks.register(
                    "rewriteRecipeOrigin" + capitalize(sourceDir.getName()), RewriteRecipeOriginTask.class,
                    task -> {
                        task.dependsOn(mainSource.getCompileJavaTaskName());
                        task.setSources(sourceDir);
                        task.setClasspath(mainSource.getOutput().getClassesDirs());
                        task.setRecipeLicense(recipeLicense);
                        task.setRepoBaseUrl(repoUrl);
                    }
            );

            copyOrigins.configure(task -> {
                task.dependsOn(attr);
                task.from(attr.get().getOutputDirectory());
                task.into(new File(project.getBuildDir(), "resources/main/META-INF/rewrite/origin"));
            });
        }
    }

    private String determineRepoUrl(Project project) {
        List<String> repoUrls = project.getExtensions()
                .getByType(PublishingExtension.class)
                .getPublications()
                .withType(MavenPublication.class)
                .stream()
                .map(MavenPublication::getPom)
                .map(DefaultMavenPom.class::cast)
                .map(DefaultMavenPom::getUrl)
                .map(Property<String>::get)
                .distinct()
                .collect(Collectors.toList());

        if (repoUrls.isEmpty()) {
            throw new IllegalStateException("Unable to find any Repository URL");
        }
        if (repoUrls.size() > 1) {
            throw new IllegalStateException("Found more than Repository URL");
        }

        return repoUrls.get(0);
    }

    private String determineLicense(Project project) {
        List<String> licenses = project.getExtensions()
                .getByType(PublishingExtension.class)
                .getPublications()
                .withType(MavenPublication.class)
                .stream()
                .map(MavenPublication::getPom)
                .map(DefaultMavenPom.class::cast)
                .map(DefaultMavenPom::getLicenses)
                .flatMap(List::stream)
                .map(MavenPomLicense::getUrl)
                .map(Property<String>::get)
                .distinct()
                .collect(Collectors.toList());

        if (licenses.isEmpty()) {
            throw new IllegalStateException("Unable to find any License");
        }
        if (licenses.size() > 1) {
            throw new IllegalStateException("Found more than one License");
        }

        return licenses.get(0);
    }
}
