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
import org.gradle.api.tasks.*;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import static org.eclipse.jgit.util.StringUtils.capitalize;

public class RewriteRecipeAuthorAttributionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        if (!new File(project.getRootDir(), ".git").exists()) {
            return;
        }
        JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
        if (java == null) {
            return;
        }
        SourceSet mainSource = java.getSourceSets().getByName("main");
        TaskContainer tasks = project.getTasks();
        TaskProvider<Copy> copyAttribution = tasks.register("copyAttribution", Copy.class);
        //noinspection UnstableApiUsage
        tasks.named("processResources", ProcessResources.class).configure(task -> {
            task.dependsOn(copyAttribution);
        });
        for (File sourceDir : mainSource.getAllSource().getSrcDirs()) {
            TaskProvider<RewriteRecipeAuthorAttributionTask> attr = tasks.register(
                    "rewriteRecipeAuthorAttribution" + capitalize(sourceDir.getName()), RewriteRecipeAuthorAttributionTask.class,
                    task -> {
                        task.dependsOn(mainSource.getCompileJavaTaskName());
                        task.setSources(sourceDir);
                        task.setClasspath(mainSource.getOutput().getClassesDirs());
                    }
            );

            copyAttribution.configure(task -> {
                task.dependsOn(attr);
                task.from(attr.get().getOutputDirectory());
                task.into(new File(project.getBuildDir(), "resources/main/META-INF/rewrite/attribution"));
            });
        }
    }
}
