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

import io.github.gradlenexus.publishplugin.NexusPublishExtension;
import io.github.gradlenexus.publishplugin.NexusPublishPlugin;
import nebula.plugin.release.NetflixOssStrategies;
import nebula.plugin.release.ReleasePlugin;
import nebula.plugin.release.git.base.ReleasePluginExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RewriteRootProjectPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(ReleasePlugin.class);
        project.getPlugins().apply(NexusPublishPlugin.class);

        project.getExtensions().configure(NexusPublishExtension.class, ext -> ext.getRepositories().sonatype());

        if (project.getExtensions().findByType(ReleasePluginExtension.class) != null) {
            project.getExtensions().configure(ReleasePluginExtension.class, ext ->
                    ext.setDefaultVersionStrategy(NetflixOssStrategies.SNAPSHOT(project)));
        }

        project.defaultTasks("build");
    }
}
