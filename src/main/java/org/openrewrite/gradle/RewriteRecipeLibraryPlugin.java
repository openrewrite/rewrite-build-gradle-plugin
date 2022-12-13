package org.openrewrite.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RewriteRecipeLibraryPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RewriteRecipeLibraryExtension ext = project.getExtensions().create("rewriteRecipe", RewriteRecipeLibraryExtension.class);
        ext.getRewriteVersion().convention(project.hasProperty("releasing") ?
            "latest.release" : "latest.integration");

        // recipe jars are generally single-module projects for which the root project is the only module
        project.getPlugins().apply(RewriteRootProjectPlugin.class);

        project.getPlugins().apply(RewriteJavaPlugin.class);
        project.getPlugins().apply(RewriteLicensePlugin.class);
        project.getPlugins().apply(RewriteMetadataPlugin.class);
        project.getPlugins().apply(RewriteDependencyCheckPlugin.class);
        project.getPlugins().apply(RewriteBuildInputLoggingPlugin.class);
        project.getPlugins().apply(RewritePublishPlugin.class);

        project.getDependencies().add("testImplementation",
                "org.openrewrite:rewrite-test:" + ext.getRewriteVersion().get());
    }
}
