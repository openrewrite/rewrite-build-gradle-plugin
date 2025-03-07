/*
 * Copyright 2021 the original author or authors.
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

public class RewriteLanguageLibraryPlugin implements Plugin<Project> {

    public void apply(Project project) {
        project.getPlugins().apply(RewriteDependencyRepositoriesPlugin.class);
        project.getPlugins().apply(RewriteJavaPlugin.class);
        project.getPlugins().apply(RewriteLicensePlugin.class);
        project.getPlugins().apply(RewriteMetadataPlugin.class);
        project.getPlugins().apply(RewriteBuildInputLoggingPlugin.class);
        project.getExtensions().create("recipeDependencies", RecipeDependenciesExtension.class);
        project.getTasks().register("createTypeTable", RecipeDependenciesTypeTableTask.class);
        project.getPlugins().apply(RewritePublishPlugin.class);
        project.getPlugins().apply(RewriteRecipeAuthorAttributionPlugin.class);
    }
}
