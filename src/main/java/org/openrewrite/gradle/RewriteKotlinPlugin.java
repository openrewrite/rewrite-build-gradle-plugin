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
