/*
 * Copyright 2023 the original author or authors.
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
import org.gradle.api.tasks.*;
import java.io.File;

public class RewriteRecipeExamplesPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        File srcDir = new File(project.getProjectDir(), "src");
        if (!srcDir.exists()) {
            return;
        }

        TaskProvider<RecipeExamplesTask> extractExamples = project.getTasks().register("extractRecipeExamples", RecipeExamplesTask.class,
            task -> task.setSources(srcDir));

        TaskProvider<Copy> copyExamples = project.getTasks().register("copyExamples", Copy.class);
        project.getTasks().named("classes").configure(task -> task.dependsOn(copyExamples));

        copyExamples.configure(task -> {
            task.dependsOn(extractExamples);
            task.from(extractExamples.get().getOutputDirectory());
            task.into(new File(project.getBuildDir(), "resources/main/META-INF/rewrite/examples"));
        });
    }
}
