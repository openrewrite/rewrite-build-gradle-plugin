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
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        SourceSet testSourceSet = sourceSets.getByName("test");
        File testDir = testSourceSet.getJava().getSourceDirectories().getSingleFile();

        if (!testDir.exists()) {
            return;
        }

        if (testDir != null) {
            project.getTasks().register("extractRecipeExamples", RecipeExamplesTask.class, task -> {
                task.setSourceDir(testDir);
                task.setDestinationDir(project.file("build/test-output"));
            });
        }
    }
}