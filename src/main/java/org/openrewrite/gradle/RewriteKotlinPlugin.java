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

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.tasks.compile.JavaCompile;
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions;
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformJvmPlugin;

public class RewriteKotlinPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(KotlinPlatformJvmPlugin.class);

        project.getTasks().withType(JavaCompile.class).getByName("compileJava", task -> {
            task.setSourceCompatibility("1.8");
            task.setTargetCompatibility("1.8");
        });

        project.getTasks().withType(KotlinCompile.class).configureEach(task -> {
            //noinspection unchecked
            task.kotlinOptions(((Action<KotlinJvmOptions>) opt -> opt.setJvmTarget("1.8")));
        });

        DependencyHandler deps = project.getDependencies();
        deps.add("testImplementation", deps.platform("org.jetbrains.kotlin:kotlin-bom"));
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-reflect");
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib");
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib-jdk8");
        deps.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib-common");
    }
}
