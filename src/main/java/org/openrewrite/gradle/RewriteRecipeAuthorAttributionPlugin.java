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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;
import org.eclipse.jgit.util.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.*;
import org.gradle.jvm.tasks.Jar;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toSet;
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
        TaskProvider<Copy> copyAttribution = project.getTasks().register("copyAttribution", Copy.class);
        project.getTasks().named("jar", Jar.class)
                .configure(task -> task.dependsOn(copyAttribution));
        Task maybeJavadoc = project.getTasks().findByPath("javadoc");
        if (maybeJavadoc != null) {
            maybeJavadoc.dependsOn(copyAttribution);
        }
        for (File sourceDir : mainSource.getAllSource().getSrcDirs()) {
            TaskProvider<RewriteRecipeAuthorAttributionTask> attr = project.getTasks().register(
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
