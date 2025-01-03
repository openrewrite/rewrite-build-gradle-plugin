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

import nebula.plugin.publishing.verification.PublishVerificationPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;


/**
 * A plugin that applies all the necessary plugins to create a recipe library in an open source environment where
 * Maven Central and Nexus Snapshots artifact repositories are available.
 * If your project does not use all the same plugins you can
 */
public class RewriteRecipeLibraryPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // recipe jars are generally single-module projects for which the root project is the only module
        project.getPlugins().apply(RewriteDependencyRepositoriesPlugin.class);
        project.getPlugins().apply(RewriteRecipeLibraryBasePlugin.class);
        project.getPlugins().apply(RewriteRootProjectPlugin.class);
        project.getPlugins().apply(RewriteJavaPlugin.class);
        project.getPlugins().apply(RewriteLicensePlugin.class);
        project.getPlugins().apply(RewriteMetadataPlugin.class);
        project.getPlugins().apply(RewriteBuildInputLoggingPlugin.class);
        project.getPlugins().apply(RewritePublishPlugin.class);
        project.getPlugins().apply(PublishVerificationPlugin.class);
        project.getPlugins().apply(RewriteRecipeAuthorAttributionPlugin.class);
//        project.getPlugins().apply(RewriteRecipeExamplesPlugin.class);
    }
}
