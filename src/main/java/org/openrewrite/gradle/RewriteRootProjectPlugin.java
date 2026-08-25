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

import nebula.plugin.info.scm.ScmInfoPlugin;
import nebula.plugin.release.NetflixOssStrategies;
import nebula.plugin.release.ReleasePlugin;
import nebula.plugin.release.git.base.ReleasePluginExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RewriteRootProjectPlugin implements Plugin<Project> {

    private static final String CLOSE_AND_RELEASE_TASK = "closeAndReleaseSonatypeStagingRepository";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(ReleasePlugin.class);
        project.getPlugins().apply(ScmInfoPlugin.class);

        // Maven Central publishing is retired — artifacts go to the Code Genome Project. The shared
        // release workflow still invokes closeAndReleaseSonatypeStagingRepository by name, so stand in
        // for the task Nexus used to contribute rather than break releases until that workflow changes.
        project.afterEvaluate(p -> {
            if (p.getTasks().findByName(CLOSE_AND_RELEASE_TASK) == null) {
                p.getTasks().register(CLOSE_AND_RELEASE_TASK, task -> {
                    task.setGroup("publishing");
                    task.setDescription("No-op. Artifacts publish to the Code Genome Project, not Maven Central.");
                });
            }
        });

        if (project.getExtensions().findByType(ReleasePluginExtension.class) != null) {
            project.getExtensions().configure(ReleasePluginExtension.class, ext ->
                    ext.setDefaultVersionStrategy(NetflixOssStrategies.SNAPSHOT(project)));
        }

        project.defaultTasks("build");
    }
}
